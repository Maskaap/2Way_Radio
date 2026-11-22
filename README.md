# PTT Connect

Internet push-to-talk for Android and iPhone, with your own branding, users, channels and admin, plus a path to real two-way radios. (*PTT Connect* is a working name: change `app_name` and `applicationId` to rename it.)

```
┌──────────────┐   HTTPS (auth, channels, floor control)   ┌───────────────────┐
│ Android app  │ ────────────────────────────────────────► │ Supabase          │
│ (Kotlin)     │                                           │ Postgres + Auth   │
│ iOS app      │   HTTPS (LiveKit token)  ┌──────────────┐ │ RLS + RPC funcs   │
│ (Swift, P2)  │ ───────────────────────► │ Vercel       │─┤ floor_state lock  │
└──────┬───────┘                          │ /api/token   │ └───────────────────┘
       │ WebRTC / Opus voice              │ /api/livekit-│        ▲
       ▼                                  │   webhook    │────────┘
┌──────────────┐  webhooks (enforcement)  │ /admin       │
│ LiveKit Cloud│ ───────────────────────► └──────────────┘
└──────────────┘
```

| Folder | What it is | State |
|---|---|---|
| `supabase/` | Database schema, security rules, floor-control functions | **Tested** against PostgreSQL with a Supabase-compatible harness |
| `vercel/` | Serverless API (LiveKit tokens, enforcement webhook, config) + admin web page | **Typechecked + unit-tested**; not yet deployed |
| `android/` | Native Kotlin app: login, channels, live voice, big PTT button, Volume-Down PTT | **Written, syntax-checked, not yet compiled**: needs your Android Studio |
| `ios/` | Swift + Apple PushToTalk framework scaffold (Phase 2) | Scaffold, not built |
| `radio-manager/` | Phase 3 foundation: driver registry, licence-safe write guard, gateway contracts | **Typechecked + tested**, no radio drivers yet |
| `docs/` | Setup, status, architecture, iOS and radio notes | |

**Start here:** [`docs/SETUP.md`](docs/SETUP.md) covers Supabase, LiveKit, Vercel, building the APK and the two-phone test.
**What works and what doesn't:** [`docs/STATUS.md`](docs/STATUS.md).
