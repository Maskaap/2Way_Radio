import SwiftUI

@main
struct PTTConnectApp: App {
    @StateObject private var backend = Backend()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(backend).preferredColorScheme(.dark) }
    }
}

/// Minimal Phase 2 test UI: sign in, enter a channel UUID, hold to talk.
struct RootView: View {
    @EnvironmentObject var backend: Backend
    @State private var email = ""
    @State private var password = ""
    @State private var channelId = ""
    @State private var error: String?
    @StateObject private var holder = Holder()

    final class Holder: ObservableObject { @Published var ptt: PushToTalkController? }

    var body: some View {
        VStack(spacing: 16) {
            if backend.session == nil {
                Image("Logo").resizable().scaledToFit().frame(width: 140, height: 140)
                Text("PTT Connect").font(.largeTitle.bold())
                TextField("E-mail", text: $email).textInputAutocapitalization(.never).textFieldStyle(.roundedBorder)
                SecureField("Password", text: $password).textFieldStyle(.roundedBorder)
                Button("Sign in") { Task { do { try await backend.signIn(email: email, password: password) } catch { self.error = error.localizedDescription } } }
                    .buttonStyle(.borderedProminent)
            } else if let ptt = holder.ptt {
                PttView(ptt: ptt, channelId: $channelId)
            } else {
                ProgressView().task {
                    let c = PushToTalkController(backend: backend); await c.setUp(); holder.ptt = c
                }
            }
            if let error { Text(error).foregroundColor(.red) }
        }.padding(24)
    }
}

struct PttView: View {
    @ObservedObject var ptt: PushToTalkController
    @Binding var channelId: String
    @State private var pressing = false

    var body: some View {
        VStack(spacing: 20) {
            Text(ptt.channelName ?? "No channel").font(.system(size: 30, weight: .bold))
            Text(ptt.transmitting ? "YOU ARE TALKING" : ptt.speaker.map { "\($0) is talking" } ?? ptt.status)
                .foregroundColor(ptt.transmitting ? .red : .green)
            if ptt.channelName == nil {
                TextField("Channel ID (UUID from admin page)", text: $channelId).textFieldStyle(.roundedBorder)
                Button("Join") { Task { await ptt.join(channelId: channelId, name: "Channel") } }.buttonStyle(.bordered)
            }
            Circle()
                .stroke(ptt.transmitting ? Color.red : Color.green, lineWidth: 6)
                .frame(width: 240, height: 240)
                .overlay(Text(ptt.transmitting ? "TALKING" : "PUSH TO TALK").bold())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { _ in if !pressing { pressing = true; ptt.pressed() } }
                    .onEnded { _ in pressing = false; ptt.released() })
        }
    }
}
