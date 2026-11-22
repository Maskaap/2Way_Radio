# Messaging, GPS tracking, geo-fencing, SOS and NFC checkpoints

Database script: `supabase/migrations/20261122000004_messaging_location_emergency.sql`. Run it once in the Supabase SQL Editor.

## Instant messaging, history and offline sync

- **In the app:** the 💬 button at the bottom right of the PTT screen opens the channel chat. A red counter shows unread messages.
- **Real-time:** after a message is saved, the sender's phone sends a tiny "new message" hint over the live voice link, and everyone in the channel fetches it from the database straight away. The text itself always comes from the database, so nobody can fake a message.
- **History:** every message is stored. Scroll up and tap **Load older messages** to go back in time.
- **Offline:** messages typed with no signal show "waiting to send" and are kept on the phone. When the connection returns they're sent automatically, without duplicates, and the phone fetches everything it missed.
- **Archive:** admin page → **Messages** → choose a channel → **Export CSV** (opens in Excel).
- **Retention:** admin → Settings → `message_retention_days` (0 = keep forever). For automatic clean-up, enable `pg_cron` and run:
  ```sql
  select cron.schedule('ptt-purge-messages', '0 2 * * *', $$select public.purge_old_messages()$$);
  select cron.schedule('ptt-purge-locations', '15 2 * * *', $$select public.purge_old_locations()$$);
  ```

## GPS location tracking

- While connected to a channel, the phone reports its position every 30 seconds. It uses Android's own GPS, with no Google Play Services needed.
- Each user can switch it off under **Settings → Share my location**. Switching it off removes them from the map.
- **Admin page → Map:** live positions with name, channel, time, battery and speed. Green means updated in the last 2 minutes. **Today's trail** draws the route.
- **Privacy:** only admins, and the user themselves, can see positions; the database enforces this. Under South Africa's **POPIA**, staff must be told that their location is tracked and why. Put this in your employment or user terms.

## Geo-fencing

- **Admin page → Map → click on the map** → enter a name and radius in metres. Orange circles are geo-fences.
- When someone enters or leaves a zone, an alert appears under **Alerts**. Their first position doesn't count, so there are no false alerts at start-up.
- Geo-fences can be turned on or off, or deleted, in the list under the map.

## Emergency alert / panic button

- **In the app:** **hold the red SOS button for 2 seconds** (a white ring fills up), so it can't go off by accident.
- What happens:
  1. The alert is stored with the phone's GPS position.
  2. A red 🚨 message is posted in the channel.
  3. Everyone in the channel gets a **loud alarm, vibration and a red banner**, with **Show on map**.
  4. The admin page shows a flashing red bar and sounds an alarm until someone clicks it.
  5. **Emergency override:** the person in trouble can talk even if someone else is holding the channel. Turn this off under Settings → `emergency_floor_override`.
- **False alarm:** the sender taps **Cancel (false alarm)**.
- **Admin:** **Alerts** → **Acknowledge** (shows "help is aware" on the phones) → **Resolve**.
- An SOS is **never blocked** by an expired subscription or plan limit.

## NFC tagging (guard patrol checkpoints)

Database script: `supabase/migrations/20261122000005_nfc_checkpoints.sql`.

**Tags:** any common NFC sticker or keyfob works (NTAG213/215/216, MIFARE Classic/Ultralight, ISO 15693). The app uses the tag's built-in serial number, so nothing needs to be written to the tag.

**Registering a checkpoint (admin):**
1. Sign in to the app with an admin account and stand at the checkpoint (the GPS position is saved with the tag).
2. Hold the back of the phone against the tag. The app shows "New tag – register it as a checkpoint?"
3. Enter a name (e.g. *Gate 3*), optionally a site, and optionally "must be scanned every … minutes".

You can also register by typing the tag ID on the admin page → **Checkpoints**.

**Guards:** tap the phone on the tag. They get a beep, a vibration and a green **✓ Checked in: Gate 3**. Time, guard and GPS position are stored.
- Works while the app is open. With the app closed, tapping a tag **opens the app and records the scan**.
- The phone must be unlocked with the screen on. Android switches NFC off when the screen is off.
- A double read within 30 seconds counts once.
- Unregistered tags are saved too, marked "unregistered", so you can see them on the admin page.

**Missed checkpoints:** if a checkpoint has a schedule and isn't scanned in time (plus `checkpoint_grace_minutes`, default 5), a **📍 Missed checkpoint** alert appears under **Alerts**. The next scan clears it automatically. The check runs whenever the admin page is open. To have it run 24/7, enable `pg_cron`:
```sql
select cron.schedule('ptt-checkpoints', '*/5 * * * *', $$select public.check_missed_checkpoints()$$);
```

**Admin page → Checkpoints:** status per checkpoint (OK / OVERDUE / no schedule), last scan and by whom, editable schedule, on/off, delete, scan history and **Export CSV** by date range. Checkpoints with a position also show as 📍 on the **Map**.

**Optional:** set `nfc_post_to_channel` = `true` to post "📍 Ben checked in at Gate 3" in the guard's channel.

## Encrypted communication

| What | How it's protected |
|---|---|
| App ↔ server (logins, messages, locations) | HTTPS / TLS. Android blocks unencrypted connections |
| Voice | WebRTC with DTLS-SRTP encryption (LiveKit) |
| Stored data | Supabase encrypts the database at rest. Row-level security means people only see their own channels |
| Passwords | Hashed with bcrypt, never stored in readable form |

*End-to-end* encryption, where even the server can't decode the voice, is possible later using LiveKit's E2EE feature, but it needs a key-sharing design. Ask if you need it.

## Cross-platform

Android is ready. iPhone and a web version are the next step. A web app can run on iPhone, Android and PC browsers without the App Store; a native iPhone app needs an Apple Developer account.
