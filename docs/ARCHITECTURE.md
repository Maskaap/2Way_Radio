# Architecture

## Components

| Part | Tech | Responsibility |
|---|---|---|
| Android app | Kotlin, Jetpack Compose, LiveKit Android 2.30, OkHttp | UI, PTT state machine, keys, audio, reconnect |
| iOS app (P2) | Swift, SwiftUI, Apple PushToTalk, LiveKit Swift | Same contract as Android |
| Database + auth | Supabase (Postgres 15+, GoTrue auth) | Users, channels, members, devices, logs, **floor lock** |
| API | Vercel serverless (TypeScript) | LiveKit tokens, enforcement webhook, public config, admin page |
| Media | LiveKit Cloud (WebRTC SFU, Opus) | Low-latency voice; one room per channel |
| Radio Manager (P3) | TypeScript module | Drivers, write safety, gateway contracts |

## One PTT press, end to end

1. The key or button goes down, and `PttEngine.pttDown()` runs. A local check comes first: if someone is already audible, the phone plays the busy tone immediately.
2. `request_floor(channel)` runs in Postgres. It does `SELECT … FOR UPDATE` on `floor_state`, so two phones pressing at the same time can't both win. The result is a grant with a 5 s lease plus a channel max time (default 60 s), or busy with the holder's name.
3. On a grant, the phone publishes its mic track to the LiveKit room (`setMicrophoneEnabled(true)`), sends a `tx_start` data hint, plays the talk-permit tone and vibrates.
4. While the button is held, the phone calls `keepalive_floor()` every 1.5 s. If keep-alive fails, if the max time is reached, or if 3 calls in a row hit network errors, the phone stops itself.
5. On release, the mic track is **unpublished and stopped** (mic fully closed), the end tone plays and `release_floor()` runs.
6. Receivers get `TrackSubscribed`, show "X is talking" and play the audio on the speaker (or a Bluetooth/wired headset if connected). `TrackUnsubscribed` marks the end.
7. The LiveKit webhook goes to Vercel. If an audio track is published by someone who **doesn't** hold the floor (a modified or buggy client), the server mutes it. If the holder leaves the room, the floor is freed at once.

## Failure handling

| Situation | What happens |
|---|---|
| Button sticks, or key-up never arrives | Activity focus loss or pause calls `releaseAll()`; the server lease expires after 5 s without keep-alive; there is a hard per-channel max time |
| Network lost while talking | Keep-alive failures stop transmission locally; the server lease frees the channel for others |
| Wi-Fi ↔ mobile data | LiveKit performs an ICE restart; on full disconnect the app reconnects with back-off (1 s → 15 s) and immediately when the network returns |
| Simultaneous presses | Postgres row lock: exactly one grant |
| App killed | Floor lease expires; `participant_left` webhook frees the floor sooner |
| Blocked device or disabled user | Refused at register and token time; disabling a user also clears any floor they hold |

## Security

- Every table has RLS. Apps call `SECURITY DEFINER` functions that check `auth.uid()`, membership and role themselves. Anonymous callers can't execute anything.
- The Supabase service-role key and LiveKit secret exist **only** in Vercel environment variables.
- LiveKit tokens are per channel, microphone-only, and subscribe-only for listen-only members.
- All traffic is TLS (HTTPS/WSS); Android blocks cleartext.
- Phone numbers and e-mails are visible only to the user themselves and admins.

## Data model

`profiles` · `channels` (public/private/direct, `max_talk_seconds`) · `channel_members` (owner/moderator/member, `can_talk`) · `floor_state` · `devices` (`blocked`) · `connection_log` · `radio_devices` (P3) · `gateways` (P3)

## Extending

- **New PTT input source** (Bluetooth button, vendor key): add a `PttSource` value and call `engine.pttDown/pttUp`. Nothing else changes.
- **New platform:** implement the same 3 calls: Supabase auth, the RPCs, and `/api/token`.
- **Gateway:** implement `GatewayAdapter`; the bridge joins the LiveKit room like a phone and uses the same floor functions.
- **Renaming:** `app_name` (Android `strings.xml`), `applicationId`, iOS `CFBundleDisplayName`, admin page title.
