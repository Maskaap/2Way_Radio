# Setup: from zero to two phones talking

Time needed: about 45 minutes. You need a Supabase account, a LiveKit Cloud account (free tier is enough for testing), a Vercel account, a computer with **Android Studio**, and two Android phones.

> **Why LiveKit Cloud?** Vercel runs short-lived functions. It can't hold open voice connections or run a media server, so the live audio goes through LiveKit. Supabase holds the data and the "who may talk" lock, and Vercel ties them together.

---

## 1. Supabase (database + logins)

1. Create a project at supabase.com. If you want the lowest delay, choose the region closest to your users. Note the region you pick.
2. **SQL Editor → New query**: paste the whole file `supabase/migrations/20261122000001_ptt_connect_init.sql` and click **Run**. It is safe to run again.
3. **Authentication → Sign In / Providers → Email**: keep Email enabled. For quick testing, turn **"Confirm email" OFF**; otherwise every new user must click a link in their e-mail before they can sign in. Turn it back on for production.
4. **Project Settings → API**: copy the **Project URL**, the **anon public** key and the **service_role** key. The service_role key is secret and goes only into Vercel, never into the app.
5. *(Optional but recommended)* **Database → Extensions → enable `pg_cron`**, then in the SQL Editor run the following. It clears stuck floors every 10 seconds; the apps already handle this themselves, so this is a second safety net:
   ```sql
   select cron.schedule('ptt-expire-floors', '10 seconds', $$select public.expire_floors()$$);
   ```

## 2. LiveKit Cloud (live voice)

1. Create a project at cloud.livekit.io.
2. **Settings → Keys**: create an API key. Copy the **WebSocket URL** (`wss://….livekit.cloud`), the **API Key** and the **API Secret**.
3. Leave this tab open. You will add the webhook in step 3.5.

## 3. Vercel (API + admin page)

1. Put this project in a Git repository (GitHub, GitLab or Bitbucket) and **import it in Vercel**.
   - **Root Directory:** `vercel`
   - **Framework preset:** Other (there is no build command)
2. **Settings → Environment Variables**: add every variable from `vercel/.env.example`:
   `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.
3. Deploy, then open `https://YOUR-APP.vercel.app/api/health`. Every check should say `set` and `database: ok`.
4. Open `https://YOUR-APP.vercel.app/admin`. Sign-in works only for admin accounts (step 5).
5. Back in **LiveKit Cloud → Settings → Webhooks**: add the URL `https://YOUR-APP.vercel.app/api/livekit-webhook` and sign it with the same API key. This enables server-side enforcement of one-talker-at-a-time.

*Vercel CLI alternative:* `cd vercel && npx vercel` and follow the prompts, then add the environment variables and run `npx vercel --prod`.

## 4. Build the Android APK

1. Install **Android Studio** (current stable). It includes the Android SDK and JDK 17.
2. **File → Open** and choose the `android` folder. Let Gradle sync; the first sync downloads a few hundred MB.
3. Create `android/local.properties` (Android Studio usually creates it and adds `sdk.dir`), and add one line:
   ```
   ptt.apiBaseUrl=https://YOUR-APP.vercel.app
   ```
   You can also change the server URL later on the login screen under **Server settings**.
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. The result is:
   `android/app/build/outputs/apk/debug/app-debug.apk`
   From a terminal you can instead run `cd android && ./gradlew assembleDebug` (on Windows: `gradlew.bat assembleDebug`).
5. Install it on both phones:
   - With USB debugging on: `adb install -r app-debug.apk`, or press **Run ▶** in Android Studio.
   - Or copy the APK to the phone and open it. You will need to allow "Install unknown apps" for your file manager.

> This code has **not yet been compiled** (the build environment I used has no access to Google's Android repository). If the first sync or build shows an error, copy the error text back to me and I'll fix it. That is the expected next step.

## 5. Make yourself admin

1. On a phone, open the app, choose **Create account** and register.
2. In the Supabase SQL Editor, run:
   ```sql
   update public.profiles set role = 'admin'
   where id = (select id from auth.users where email = 'you@example.com');
   ```
3. Sign in at `https://YOUR-APP.vercel.app/admin`.

## 6. Two-phone test (Phase 1 acceptance)

| # | Step | Expected |
|---|---|---|
| 1 | Phone A: create account, allow microphone + notifications | Channel list appears |
| 2 | Phone A: **New channel** "Test" (public) | Opens the PTT screen; status **Connected**, network chip shows Wi-Fi or Mobile data |
| 3 | Phone B: second account, tap "Test" | Both phones show "2 online" |
| 4 | A: hold the big button | Short beep, vibration, red **YOU ARE TALKING** with countdown; **Mic ON** |
| 5 | B | Hears A, shows **"A is talking"** in blue; incoming beep |
| 6 | A: release | End beep; mic indicator off; Android's green mic dot disappears |
| 7 | A holds; while A talks, B presses | B gets a busy tone and **Channel busy** |
| 8 | A: hold **Volume Down** | Same as the screen button; system volume does **not** change |
| 9 | Phone A on Wi-Fi, B on 4G/5G (Wi-Fi off) | Talk both ways |
| 10 | While A is idle: turn A's Wi-Fi off | Chip changes to Mobile data; within a few seconds **Reconnecting → Connected** |
| 11 | A: hold Volume Down and pull the network (airplane mode) | Within about 5 s A stops with an error tone; B's channel frees up |
| 12 | A: hold the button for 60 s | Cut at 60 s ("Maximum talk time reached") |
| 13 | B: lock the screen; A talks | B still hears A (foreground notification shows "A is talking") |
| 14 | Admin page → Devices → **Block** B → B switches channel | B sees "This phone has been blocked…" |

Please report each step as ✅ or ❌, with what happened.

## 7. Local database testing (optional, for developers)

The schema test in this repo runs against plain PostgreSQL with a small shim for Supabase's `auth` schema and roles. With the Supabase CLI you can instead run `supabase start` and `supabase db reset`, which applies `supabase/migrations/` automatically.
