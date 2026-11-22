# iPhone (Phase 2)

## What Apple allows

- **Apple PushToTalk framework (iOS 16+)** is the official route. It gives you:
  - the system PTT interface (lock screen, Dynamic Island) with a talk button,
  - transmit/receive while the app is in the background,
  - events from supported Bluetooth PTT accessories.
- **Action Button** (iPhone 15 Pro and later): the user assigns it in *Settings › Action Button*, e.g. to open the app or run a Shortcut. How far it can directly key the PTT channel depends on Apple's current Action Button options for PushToTalk apps. Test it on a device; don't assume.
- **Volume and power buttons can't be reprogrammed** by apps. We don't attempt it.
- **Background receive** works through an Apple Push Notification of type `pushtotalk`, sent by your server when someone starts talking. The app hands the system the speaker's name and the audio then plays.

## What's in `ios/`

- `project.yml`: XcodeGen spec (adds LiveKit Swift and PushToTalk.framework)
- `Backend.swift`: same backend contract as Android
- `PushToTalkController.swift`: PTChannelManager, floor control, LiveKit audio, mic closed when idle
- `PTTConnectApp.swift`: minimal test UI

**State:** written against LiveKit Swift 2.19 and the PushToTalk API. **Not built or run yet.**

## To test on an iPhone

1. A Mac with Xcode (current version), an **Apple Developer Program** membership, and an iPhone on iOS 16+.
2. In the developer portal, create the App ID `com.pttconnect.app` and enable **Push to Talk** and **Push Notifications**.
3. `brew install xcodegen && cd ios && xcodegen generate && open PTTConnect.xcodeproj`
4. Select your team under *Signing & Capabilities*, connect the iPhone, then **Run**.
5. For distribution to testers, use TestFlight.

## Still to build for Phase 2

- Server: store each phone's ephemeral PTT push token (from `receivedEphemeralPushToken`) and send APNs `pushtotalk` pushes from Vercel when a floor is granted. This needs an APNs key from your Apple account.
- Full channel list, settings and tones UI to match Android.
