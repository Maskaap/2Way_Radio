import AVFoundation
import Foundation
import LiveKit
import PushToTalk

/// iOS PTT using Apple's PushToTalk framework (iOS 16+) + LiveKit audio.
///
/// Why the framework: it is the only Apple-approved way to get a system PTT UI (lock screen /
/// Dynamic Island talk button), Bluetooth PTT accessory events, and background reception.
/// The Action Button can be pointed at the app via Settings › Action Button (on supported models);
/// Apple does not allow apps to reprogram the volume or power buttons.
///
/// STATUS: Phase 2 scaffold – written against LiveKit Swift 2.19 and the PushToTalk API, but NOT yet
/// built or tested on a device. Background reception additionally needs APNs PTT pushes from the
/// server (see docs/IOS.md).
@MainActor
final class PushToTalkController: NSObject, ObservableObject {
    @Published var channelName: String?
    @Published var transmitting = false
    @Published var speaker: String?
    @Published var status = "Not connected"

    private let backend: Backend
    private var manager: PTChannelManager?
    private let room = Room()
    private var channelId: String?
    private var channelUUID: UUID?
    private var keepalive: Task<Void, Never>?

    init(backend: Backend) {
        self.backend = backend
        super.init()
        // The PushToTalk framework owns activation of the audio session; LiveKit must not.
        AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = false
        room.add(delegate: self)
    }

    func setUp() async {
        do { manager = try await PTChannelManager.channelManager(delegate: self, restorationDelegate: self) }
        catch { status = "PTT framework unavailable: \(error.localizedDescription)" }
    }

    /// Joins a channel in the system PTT UI and connects the LiveKit room for audio.
    func join(channelId: String, name: String) async {
        guard let manager, let uuid = UUID(uuidString: channelId) else { return }
        do {
            if try await backend.registerDevice() { status = "This phone is blocked"; return }
            _ = try await backend.rpc("enter_channel", ["p_channel_id": channelId])
            let lk = try await backend.liveKitToken(channelId)
            try await room.connect(url: lk.url, token: lk.token)
            self.channelId = channelId; channelUUID = uuid; channelName = name
            manager.requestJoinChannel(channelUUID: uuid, descriptor: PTChannelDescriptor(name: name, image: nil))
            status = "Connected"
        } catch { status = error.localizedDescription }
    }

    // On-screen button → ask the system to start; the delegate callbacks do the actual work.
    func pressed()  { if let m = manager, let u = channelUUID { m.requestBeginTransmitting(channelUUID: u) } }
    func released() { if let m = manager, let u = channelUUID { m.stopTransmitting(channelUUID: u) } }

    private func beginTransmit() async {
        guard let channelId else { return }
        do {
            let floor = try await backend.requestFloor(channelId)
            guard floor.granted else {
                speaker = floor.holder; status = "Channel busy"
                if let m = manager, let u = channelUUID { m.stopTransmitting(channelUUID: u) }
                return
            }
            try await room.localParticipant.setMicrophone(enabled: true)
            transmitting = true
            keepalive = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    guard let self, let id = self.channelId else { return }
                    let ok = ((try? await self.backend.rpc("keepalive_floor", ["p_channel_id": id])) as? [String: Any])?["ok"] as? Bool ?? false
                    if !ok { await self.endTransmit(); if let m = self.manager, let u = self.channelUUID { m.stopTransmitting(channelUUID: u) }; return }
                }
            }
        } catch { status = error.localizedDescription }
    }

    private func endTransmit() async {
        keepalive?.cancel(); keepalive = nil
        // Unpublish = microphone fully closed while not transmitting.
        if let pub = room.localParticipant.firstAudioPublication as? LocalTrackPublication {
            try? await room.localParticipant.unpublish(publication: pub)
        }
        transmitting = false
        if let id = channelId { _ = try? await backend.rpc("release_floor", ["p_channel_id": id]) }
    }
}

extension PushToTalkController: PTChannelManagerDelegate {
    nonisolated func channelManager(_ m: PTChannelManager, didJoinChannel uuid: UUID, reason: PTChannelJoinReason) {}
    nonisolated func channelManager(_ m: PTChannelManager, didLeaveChannel uuid: UUID, reason: PTChannelLeaveReason) {
        Task { @MainActor in await self.room.disconnect(); self.status = "Left channel" }
    }
    nonisolated func channelManager(_ m: PTChannelManager, channelUUID: UUID, didBeginTransmittingFrom source: PTChannelTransmitRequestSource) {
        Task { @MainActor in await self.beginTransmit() }
    }
    nonisolated func channelManager(_ m: PTChannelManager, channelUUID: UUID, didEndTransmittingFrom source: PTChannelTransmitRequestSource) {
        Task { @MainActor in await self.endTransmit() }
    }
    nonisolated func channelManager(_ m: PTChannelManager, receivedEphemeralPushToken pushToken: Data) {
        // Phase 2: send this token to the backend so it can wake this phone with an APNs "pushtotalk" push.
    }
    nonisolated func incomingPushResult(channelManager: PTChannelManager, channelUUID: UUID, pushPayload: [String: Any]) -> PTPushResult {
        let name = pushPayload["speaker"] as? String ?? "Someone"
        return .activeRemoteParticipant(PTParticipant(name: name, image: nil))
    }
    nonisolated func channelManager(_ m: PTChannelManager, didActivate audioSession: AVAudioSession) {}
    nonisolated func channelManager(_ m: PTChannelManager, didDeactivate audioSession: AVAudioSession) {}
}

extension PushToTalkController: PTChannelRestorationDelegate {
    nonisolated func channelDescriptor(restoredChannelUUID channelUUID: UUID) -> PTChannelDescriptor {
        PTChannelDescriptor(name: UserDefaults.standard.string(forKey: "last_channel_name") ?? "PTT Connect", image: nil)
    }
}

extension PushToTalkController: RoomDelegate {
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) {
        let name = participant.name ?? "Someone"
        Task { @MainActor in
            self.speaker = name
            if let m = self.manager, let u = self.channelUUID {
                try? await m.setActiveRemoteParticipant(PTParticipant(name: name, image: nil), channelUUID: u)
            }
        }
    }
    nonisolated func room(_ room: Room, participant: RemoteParticipant, didUnsubscribeTrack publication: RemoteTrackPublication) {
        Task { @MainActor in
            self.speaker = nil
            if let m = self.manager, let u = self.channelUUID { try? await m.setActiveRemoteParticipant(nil, channelUUID: u) }
        }
    }
}
