# Status: what's done, what's tested, what's not

Rule used here: something is ✅ only if it was **actually run and checked**. "Written" means the code exists but hasn't run yet.

## Phase 1: working walkie-talkie

| Feature | State | Evidence / what's left |
|---|---|---|
| Database schema, RLS security, RPC functions | ✅ Tested | Applied twice (re-runnable) to PostgreSQL 16. Behaviour test run as real `authenticated` users |
| Floor control: one talker per channel | ✅ Tested (DB) | Grant; busy for the 2nd user with the holder's name; release then grant; listen-only refused |
| Stuck button / lost network frees the floor | ✅ Tested (DB) | Expired 5 s lease lets the next user take over; keep-alive fails after max talk time |
| Private channels, members, private 1:1 calls | ✅ Tested (DB) | Non-member refused; add member; direct call reuses the same channel |
| Device registration + blocking | ✅ Tested (DB) | Admin blocks; phone gets `blocked` + reason; non-admin can't block |
| Privacy: users can't read others' phone numbers or e-mails, or the logs | ✅ Tested (DB) | Profile reads limited to self/admin; names only via roster |
| Vercel token API decision rules | ✅ Unit-tested | Blocked device, disabled account, non-member, listen-only (subscribe-only token) |
| LiveKit token contents + webhook signature check | ✅ Unit-tested | Token verified with the LiveKit SDK; forged webhook rejected |
| Vercel functions deployed against real Supabase + LiveKit | ⏳ Needs your deploy | Run `/api/health` after deploying |
| Admin web page | 📝 Written | Not run in a browser yet. Needs the deployed Vercel + Supabase |
| Android app: login, register, channels, create/join, members, private call | 📝 Written + syntax-checked | **Not compiled yet.** First Android Studio build may surface small compile fixes |
| Android live voice (LiveKit 2.30, Opus/WebRTC) | 📝 Written | API calls checked against the LiveKit SDK source. Needs a test on 2 phones |
| Big PTT button, tones (adjustable), busy tone, speaker name, mic/speaker status | 📝 Written | Needs the phone test |
| **Volume Down = PTT (app on screen)** | 📝 Written | `dispatchKeyEvent` swallows the key so volume doesn't change. Phone test |
| Volume Up = volume **or** next channel (setting) | 📝 Written | |
| Learn any other hardware key as PTT (when Android delivers it as a key event) | 📝 Written | Works only for keys that reach apps; many rugged-phone PTT keys don't (Phase 2) |
| Wi-Fi ↔ 4G/5G switching + auto-reconnect | 📝 Written | LiveKit migrates the media path; the app also reconnects with back-off. Phone test |
| Mic closed when not transmitting | 📝 Written | The mic track is unpublished **and stopped** on release |
| Receive with screen off (foreground service) | 📝 Written | Phone test (step 13). Some brands' battery savers kill apps; you may need to exempt the app from battery optimisation |
| Safe handling: key-up lost on focus change, max talk time, keep-alive failure | 📝 Written | |

## Phase 2: not started (by design)

- Bluetooth PTT buttons and speaker-mics. These are usually media-button (`KEYCODE_HEADSETHOOK` / `MEDIA_PLAY_PAUSE`) or vendor BLE protocols, handled per device.
- Talking with the **screen off** on Android. A normal app doesn't receive volume keys in the background. The options are a MediaSession volume provider (press events only, so toggle-to-talk rather than hold), vendor PTT-key broadcast intents on rugged phones (per model), or an Accessibility service (Google Play restricts this to genuine accessibility use).
- iOS: `ios/` is a scaffold using Apple's PushToTalk framework (see `docs/IOS.md`). Background receive also needs APNs push sending from the server.
- Advanced audio: AGC, noise suppression tuning, per-channel volume.

## Phase 3: foundation only

- `radio-manager/`: driver registry (reports **unsupported** unless a driver positively identifies the radio), write guard (backup + typed confirmation + **licensed frequency and power check**), and RoIP gateway contracts. ✅ 7 tests.
- Radio registry table + admin "Radios" tab (inventory only).
- **No radio is read or programmed yet.** No drivers are included.

## What I cannot do for you (needs you, hardware or accounts)

| Item | Why |
|---|---|
| Supabase, LiveKit Cloud and Vercel accounts and keys | Your accounts |
| Compile the APK | Google's Android repository was not reachable from my build environment. You build it in Android Studio (5 clicks, see SETUP.md) |
| Two-phone tests | Physical phones |
| iOS build | Needs a Mac with Xcode and an Apple Developer account ($99/yr) with the **Push to Talk** capability enabled |
| Power button as PTT | **Not possible** for normal Android apps. The system reserves it and never delivers it to apps. On iPhone, also not possible. Alternatives: Volume Down, a learned key, Bluetooth PTT button (Phase 2), the iPhone Action Button via Apple's PushToTalk framework |
| Radio programming | Needs each vendor's documented protocol or licensed SDK/CPS, the actual radio and cable, and your licence details |
