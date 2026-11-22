# Supabase

Run `migrations/20261122000001_ptt_connect_init.sql` in the Supabase SQL Editor (or `supabase db push` with the CLI). It is safe to run more than once.

Make the first admin after registering in the app:

```sql
update public.profiles set role = 'admin'
where id = (select id from auth.users where email = 'you@example.com');
```

Optional extra safety net for stuck floors (enable the `pg_cron` extension first):

```sql
select cron.schedule('ptt-expire-floors', '10 seconds', $$select public.expire_floors()$$);
```

### Functions the apps call (all check permissions themselves)

| Function | Purpose |
|---|---|
| `register_device(uid, platform, model, version)` | Register this phone; returns `{blocked, reason}` |
| `my_channels()` | Channels I can see, with membership and online counts |
| `create_channel(name, kind)` | New public/private channel; the caller becomes owner |
| `enter_channel(id)` / `exit_channel(id)` | Switch channel (auto-joins public channels) |
| `channel_roster(id)` | Members + online state (names only) |
| `find_users(query)` | Exact e-mail or phone, or name prefix |
| `add_member` / `remove_member` | Owner, moderator or admin |
| `open_direct_call(user)` | Private 1:1 channel (re-used) |
| `request_floor` / `keepalive_floor` / `release_floor` | Half-duplex floor control |
| `heartbeat(channel)` | Online status (every 30 s) |
| `admin_*` | Admin page actions |
