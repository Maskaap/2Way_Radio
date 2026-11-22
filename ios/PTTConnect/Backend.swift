import Foundation
import UIKit

/// Same backend contract as the Android app: Vercel (/api/config, /api/token) + Supabase Auth + RPCs.
@MainActor
final class Backend: ObservableObject {
    struct Session: Codable { var accessToken: String; var refreshToken: String; var expiresAt: Double; var userId: String; var name: String }
    struct BackendError: LocalizedError { let code: String; var errorDescription: String? { code } }

    @Published private(set) var session: Session?
    private var supabaseURL: String?
    private var anonKey: String?

    var apiBase: String {
        get { UserDefaults.standard.string(forKey: "api_base") ?? (Bundle.main.object(forInfoDictionaryKey: "PTTApiBaseURL") as? String ?? "") }
        set { UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "api_base"); supabaseURL = nil }
    }

    /// Random per-install id used for device registration/blocking.
    let deviceUid: String = {
        if let id = UserDefaults.standard.string(forKey: "device_uid") { return id }
        let id = UUID().uuidString; UserDefaults.standard.set(id, forKey: "device_uid"); return id
    }()

    init() {
        if let d = UserDefaults.standard.data(forKey: "session") { session = try? JSONDecoder().decode(Session.self, from: d) }
    }

    func ensureConfig() async throws {
        if supabaseURL != nil { return }
        let o = try await json(URLRequest(url: URL(string: "\(apiBase)/api/config")!))
        supabaseURL = o["supabaseUrl"] as? String
        anonKey = o["supabaseAnonKey"] as? String
    }

    func signIn(email: String, password: String) async throws {
        try await ensureConfig()
        store(try await json(sb("/auth/v1/token?grant_type=password", body: ["email": email, "password": password])))
    }

    func signOut() { session = nil; UserDefaults.standard.removeObject(forKey: "session") }

    func rpc(_ fn: String, _ args: [String: Any] = [:]) async throws -> Any {
        try await ensureConfig()
        var r = sb("/rest/v1/rpc/\(fn)", body: args)
        r.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        try check(resp, data)
        return data.isEmpty ? NSNull() : (try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    func registerDevice() async throws -> Bool {
        let o = try await rpc("register_device", ["p_device_uid": deviceUid, "p_platform": "ios",
                                                  "p_model": UIDevice.current.model, "p_app_version": "0.1.0"]) as? [String: Any]
        return (o?["blocked"] as? Bool) ?? false
    }

    func requestFloor(_ channelId: String) async throws -> (granted: Bool, holder: String?, maxMs: Int) {
        let o = try await rpc("request_floor", ["p_channel_id": channelId]) as? [String: Any] ?? [:]
        return ((o["granted"] as? Bool) ?? false, o["holder_name"] as? String, (o["max_ms"] as? Int) ?? 60_000)
    }

    func liveKitToken(_ channelId: String) async throws -> (url: String, token: String) {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/token")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["channelId": channelId, "deviceUid": deviceUid])
        let o = try await json(r)
        return (o["url"] as? String ?? "", o["token"] as? String ?? "")
    }

    // MARK: - plumbing
    private func token() async throws -> String {
        guard let s = session else { throw BackendError(code: "not_signed_in") }
        if s.expiresAt - Date().timeIntervalSince1970 > 60 { return s.accessToken }
        store(try await json(sb("/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": s.refreshToken])))
        return session!.accessToken
    }

    private func store(_ o: [String: Any]) {
        guard let user = o["user"] as? [String: Any], let at = o["access_token"] as? String, let rt = o["refresh_token"] as? String else { return }
        let meta = user["user_metadata"] as? [String: Any]
        let s = Session(accessToken: at, refreshToken: rt,
                        expiresAt: (o["expires_at"] as? Double) ?? Date().timeIntervalSince1970 + 3600,
                        userId: user["id"] as? String ?? "", name: meta?["display_name"] as? String ?? "me")
        session = s
        UserDefaults.standard.set(try? JSONEncoder().encode(s), forKey: "session")
    }

    private func sb(_ path: String, body: [String: Any]) -> URLRequest {
        var r = URLRequest(url: URL(string: "\(supabaseURL ?? "")\(path)")!)
        r.httpMethod = "POST"
        r.setValue(anonKey, forHTTPHeaderField: "apikey")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return r
    }

    private func json(_ r: URLRequest) async throws -> [String: Any] {
        let (data, resp) = try await URLSession.shared.data(for: r)
        try check(resp, data)
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func check(_ resp: URLResponse, _ data: Data) throws {
        guard let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) else { return }
        let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        throw BackendError(code: (o?["error"] ?? o?["error_code"] ?? o?["message"]) as? String ?? "http_\(h.statusCode)")
    }
}
