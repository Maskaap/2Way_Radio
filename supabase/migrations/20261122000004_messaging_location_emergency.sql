-- =====================================================================
-- PTT Connect – instant messaging (+history, offline sync), GPS tracking,
-- geo-fencing and emergency / panic alerts.
-- Run AFTER 20261122000003_channel_management.sql. Safe to run again.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Settings for these features (editable in admin → Settings)
-- ---------------------------------------------------------------------
insert into public.app_settings (key, value, description) values
  ('message_retention_days', '0',   'Delete chat messages older than this many days (0 = keep forever).'),
  ('location_interval_seconds', '30', 'How often phones report their position while connected.'),
  ('location_history_days', '30',   'Keep location history (trails) for this many days.'),
  ('emergency_floor_override', 'true', 'true = a user in emergency may talk even if someone else holds the channel.')
on conflict (key) do nothing;

-- ---------------------------------------------------------------------
-- MESSAGES
-- ---------------------------------------------------------------------
create table if not exists public.messages (
  id          bigserial primary key,
  channel_id  uuid not null references public.channels(id) on delete cascade,
  sender_id   uuid references public.profiles(id) on delete set null,
  sender_name text not null,
  kind        text not null default 'text' check (kind in ('text','system','emergency')),
  body        text not null check (char_length(body) between 1 and 2000),
  client_id   uuid unique,                 -- set by the phone; makes offline re-sends safe (no duplicates)
  created_at  timestamptz not null default now()
);
create index if not exists messages_channel_idx on public.messages(channel_id, id);

alter table public.messages enable row level security;
drop policy if exists messages_read on public.messages;
create policy messages_read on public.messages for select to authenticated
  using (public.is_member(channel_id) or public.is_admin());

-- Send a message. Idempotent per client_id: re-sending after a lost connection returns the original.
create or replace function public.send_message(p_channel_id uuid, p_body text, p_client_id uuid default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); m public.messages; nm text;
begin
  perform public.assert_access(uid);
  if not public.is_member(p_channel_id) then raise exception 'not_a_member' using errcode = '42501'; end if;
  if p_body is null or char_length(trim(p_body)) = 0 then raise exception 'empty_message'; end if;
  if p_client_id is not null then
    select * into m from public.messages where client_id = p_client_id;
    if found then return to_jsonb(m); end if;
  end if;
  select display_name into nm from public.profiles where id = uid;
  insert into public.messages(channel_id, sender_id, sender_name, body, client_id)
  values (p_channel_id, uid, nm, left(trim(p_body), 2000), p_client_id)
  returning * into m;
  return to_jsonb(m);
end $$;

-- Newer messages (sync after being offline). Oldest first.
create or replace function public.messages_since(p_channel_id uuid, p_after_id bigint default 0, p_limit integer default 200)
returns setof public.messages language plpgsql stable security definer set search_path = public as $$
begin
  if not (public.is_member(p_channel_id) or public.is_admin()) then raise exception 'not_a_member' using errcode = '42501'; end if;
  return query select * from public.messages
    where channel_id = p_channel_id and id > coalesce(p_after_id, 0)
    order by id asc limit least(greatest(p_limit, 1), 500);
end $$;

-- Older messages (scroll back through history). Returned oldest first.
create or replace function public.messages_before(p_channel_id uuid, p_before_id bigint, p_limit integer default 50)
returns setof public.messages language plpgsql stable security definer set search_path = public as $$
begin
  if not (public.is_member(p_channel_id) or public.is_admin()) then raise exception 'not_a_member' using errcode = '42501'; end if;
  return query select * from (
    select * from public.messages where channel_id = p_channel_id and id < p_before_id
    order by id desc limit least(greatest(p_limit, 1), 200)) x order by id asc;
end $$;

create or replace function public.purge_old_messages()
returns integer language plpgsql security definer set search_path = public as $$
declare d integer := coalesce((public.setting('message_retention_days'))::int, 0); n integer := 0;
begin
  if d > 0 then
    delete from public.messages where created_at < now() - make_interval(days => d);
    get diagnostics n = row_count;
  end if;
  return n;
end $$;

-- ---------------------------------------------------------------------
-- LOCATION
-- ---------------------------------------------------------------------
create table if not exists public.user_locations (
  user_id     uuid primary key references public.profiles(id) on delete cascade,
  lat         double precision not null check (lat between -90 and 90),
  lng         double precision not null check (lng between -180 and 180),
  accuracy_m  real,
  speed_mps   real,
  heading     real,
  battery_pct smallint,
  channel_id  uuid references public.channels(id) on delete set null,
  updated_at  timestamptz not null default now()
);

create table if not exists public.location_history (
  id          bigserial primary key,
  user_id     uuid not null references public.profiles(id) on delete cascade,
  lat         double precision not null,
  lng         double precision not null,
  accuracy_m  real,
  recorded_at timestamptz not null default now()
);
create index if not exists location_history_user_idx on public.location_history(user_id, recorded_at desc);

create table if not exists public.geofences (
  id             uuid primary key default gen_random_uuid(),
  name           text not null check (char_length(name) between 1 and 80),
  lat            double precision not null check (lat between -90 and 90),
  lng            double precision not null check (lng between -180 and 180),
  radius_m       integer not null check (radius_m between 20 and 100000),
  alert_on_enter boolean not null default true,
  alert_on_exit  boolean not null default true,
  active         boolean not null default true,
  created_by     uuid references public.profiles(id) on delete set null,
  created_at     timestamptz not null default now()
);

create table if not exists public.geofence_state (
  user_id     uuid not null references public.profiles(id) on delete cascade,
  geofence_id uuid not null references public.geofences(id) on delete cascade,
  inside      boolean not null,
  updated_at  timestamptz not null default now(),
  primary key (user_id, geofence_id)
);

-- ---------------------------------------------------------------------
-- ALERTS (emergency + geofence)
-- ---------------------------------------------------------------------
create table if not exists public.alerts (
  id              bigserial primary key,
  kind            text not null check (kind in ('emergency','geofence_enter','geofence_exit')),
  status          text not null default 'open' check (status in ('open','acknowledged','resolved','cancelled')),
  user_id         uuid references public.profiles(id) on delete set null,
  user_name       text,
  channel_id      uuid references public.channels(id) on delete set null,
  geofence_id     uuid references public.geofences(id) on delete set null,
  lat             double precision,
  lng             double precision,
  message         text,
  created_at      timestamptz not null default now(),
  acknowledged_by uuid references public.profiles(id) on delete set null,
  acknowledged_at timestamptz,
  resolved_at     timestamptz
);
create index if not exists alerts_open_idx on public.alerts(status, created_at desc);

alter table public.user_locations   enable row level security;
alter table public.location_history enable row level security;
alter table public.geofences        enable row level security;
alter table public.geofence_state   enable row level security;
alter table public.alerts           enable row level security;

-- Privacy: only the person themselves and admins see positions.
drop policy if exists loc_read on public.user_locations;
create policy loc_read on public.user_locations for select to authenticated using (user_id = auth.uid() or public.is_admin());
drop policy if exists loch_read on public.location_history;
create policy loch_read on public.location_history for select to authenticated using (user_id = auth.uid() or public.is_admin());
drop policy if exists gf_read on public.geofences;
create policy gf_read on public.geofences for select to authenticated using (public.is_admin());
drop policy if exists gf_admin on public.geofences;
create policy gf_admin on public.geofences for all to authenticated using (public.is_admin()) with check (public.is_admin());
drop policy if exists gfs_read on public.geofence_state;
create policy gfs_read on public.geofence_state for select to authenticated using (public.is_admin());
-- Alerts: admins see all; channel members see emergencies in their channels; you see your own.
drop policy if exists alerts_read on public.alerts;
create policy alerts_read on public.alerts for select to authenticated
  using (public.is_admin() or user_id = auth.uid() or (kind = 'emergency' and channel_id is not null and public.is_member(channel_id)));

create or replace function public.distance_m(lat1 double precision, lng1 double precision, lat2 double precision, lng2 double precision)
returns double precision language sql immutable as $$
  select 2 * 6371000 * asin(sqrt(
    power(sin(radians(lat2 - lat1) / 2), 2) +
    cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lng2 - lng1) / 2), 2)));
$$;

-- Phone reports its position. Updates live map, keeps a trail (max 1 point / minute),
-- and raises geofence enter/exit alerts.
create or replace function public.report_location(p_lat double precision, p_lng double precision,
  p_accuracy real default null, p_speed real default null, p_heading real default null, p_battery smallint default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); nm text; ch uuid; g record; was boolean; now_in boolean;
        raised jsonb := '[]'::jsonb; last_t timestamptz; aid bigint;
begin
  if p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid_location';
  end if;
  select display_name, current_channel_id into nm, ch from public.profiles where id = uid;

  insert into public.user_locations(user_id, lat, lng, accuracy_m, speed_mps, heading, battery_pct, channel_id, updated_at)
  values (uid, p_lat, p_lng, p_accuracy, p_speed, p_heading, p_battery, ch, now())
  on conflict (user_id) do update set lat = excluded.lat, lng = excluded.lng, accuracy_m = excluded.accuracy_m,
    speed_mps = excluded.speed_mps, heading = excluded.heading, battery_pct = excluded.battery_pct,
    channel_id = excluded.channel_id, updated_at = now();

  select max(recorded_at) into last_t from public.location_history where user_id = uid;
  if last_t is null or last_t < now() - interval '55 seconds' then
    insert into public.location_history(user_id, lat, lng, accuracy_m) values (uid, p_lat, p_lng, p_accuracy);
  end if;

  for g in select * from public.geofences where active loop
    now_in := public.distance_m(p_lat, p_lng, g.lat, g.lng) <= g.radius_m;
    select inside into was from public.geofence_state where user_id = uid and geofence_id = g.id;
    if was is null then
      insert into public.geofence_state(user_id, geofence_id, inside) values (uid, g.id, now_in);   -- first fix: no alert
    elsif was <> now_in then
      update public.geofence_state set inside = now_in, updated_at = now() where user_id = uid and geofence_id = g.id;
      if (now_in and g.alert_on_enter) or (not now_in and g.alert_on_exit) then
        insert into public.alerts(kind, user_id, user_name, channel_id, geofence_id, lat, lng, message)
        values (case when now_in then 'geofence_enter' else 'geofence_exit' end, uid, nm, ch, g.id, p_lat, p_lng,
                nm || case when now_in then ' entered ' else ' left ' end || g.name)
        returning id into aid;
        raised := raised || jsonb_build_object('alert_id', aid, 'geofence', g.name, 'entered', now_in);
      end if;
    end if;
  end loop;
  return jsonb_build_object('ok', true, 'alerts', raised);
end $$;

create or replace function public.purge_old_locations()
returns integer language plpgsql security definer set search_path = public as $$
declare d integer := coalesce((public.setting('location_history_days'))::int, 30); n integer;
begin
  delete from public.location_history where recorded_at < now() - make_interval(days => greatest(d, 1));
  get diagnostics n = row_count; return n;
end $$;

-- Phone stops sharing location: remove it from the live map.
create or replace function public.stop_location_sharing()
returns void language sql security definer set search_path = public as $$
  delete from public.user_locations where user_id = auth.uid();
$$;

-- ---------------------------------------------------------------------
-- EMERGENCY / PANIC
-- ---------------------------------------------------------------------
create or replace function public.raise_emergency(p_channel_id uuid default null, p_lat double precision default null,
  p_lng double precision default null, p_message text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); nm text; a public.alerts; ch uuid := p_channel_id;
begin
  -- An emergency is never blocked by subscription or plan limits.
  select display_name into nm from public.profiles where id = uid;
  if ch is not null and not public.is_member(ch) then ch := null; end if;

  -- Pressing again while an emergency is open just updates its location (no alert flood).
  select * into a from public.alerts where user_id = uid and kind = 'emergency' and status in ('open','acknowledged')
    order by id desc limit 1;
  if found then
    update public.alerts set lat = coalesce(p_lat, lat), lng = coalesce(p_lng, lng), channel_id = coalesce(ch, channel_id)
     where id = a.id returning * into a;
    return to_jsonb(a) || jsonb_build_object('repeat', true);
  end if;

  insert into public.alerts(kind, user_id, user_name, channel_id, lat, lng, message)
  values ('emergency', uid, nm, ch, p_lat, p_lng, left(coalesce(nullif(trim(p_message), ''), 'Emergency – needs help'), 500))
  returning * into a;
  if ch is not null then
    insert into public.messages(channel_id, sender_id, sender_name, kind, body)
    values (ch, uid, nm, 'emergency', '🚨 EMERGENCY from ' || nm ||
      case when p_lat is not null then ' – location ' || round(p_lat::numeric, 5) || ', ' || round(p_lng::numeric, 5) else '' end);
  end if;
  if p_lat is not null then
    insert into public.user_locations(user_id, lat, lng, channel_id) values (uid, p_lat, p_lng, ch)
    on conflict (user_id) do update set lat = excluded.lat, lng = excluded.lng, updated_at = now();
  end if;
  insert into public.connection_log(event, user_id, channel_id, detail)
  values ('emergency', uid, ch, jsonb_build_object('alert_id', a.id, 'lat', p_lat, 'lng', p_lng));
  return to_jsonb(a) || jsonb_build_object('repeat', false);
end $$;

-- Acknowledge / resolve (admins), or cancel your own (false alarm).
create or replace function public.update_alert(p_alert_id bigint, p_status text)
returns void language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); a public.alerts;
begin
  select * into a from public.alerts where id = p_alert_id;
  if not found then raise exception 'alert_not_found'; end if;
  if p_status = 'cancelled' then
    if a.user_id <> uid and not public.is_admin() then raise exception 'not_allowed' using errcode = '42501'; end if;
  elsif p_status in ('acknowledged','resolved') then
    if not public.is_admin() then raise exception 'not_allowed' using errcode = '42501'; end if;
  else raise exception 'invalid_status'; end if;
  update public.alerts set status = p_status,
    acknowledged_by = case when p_status = 'acknowledged' then uid else acknowledged_by end,
    acknowledged_at = case when p_status = 'acknowledged' then now() else acknowledged_at end,
    resolved_at = case when p_status in ('resolved','cancelled') then now() else resolved_at end
   where id = p_alert_id;
  if a.kind = 'emergency' and a.channel_id is not null and p_status in ('resolved','cancelled') then
    insert into public.messages(channel_id, sender_id, sender_name, kind, body)
    select a.channel_id, uid, p.display_name, 'system',
           case when p_status = 'cancelled' then 'Emergency from ' || coalesce(a.user_name, '?') || ' was cancelled'
                else 'Emergency from ' || coalesce(a.user_name, '?') || ' resolved by ' || p.display_name end
    from public.profiles p where p.id = uid;
  end if;
end $$;

-- Open emergencies a phone should show in its current channel (plus its own).
create or replace function public.open_emergencies(p_channel_id uuid default null)
returns setof public.alerts language sql stable security definer set search_path = public as $$
  select * from public.alerts
  where kind = 'emergency' and status in ('open','acknowledged') and created_at > now() - interval '24 hours'
    and (user_id = auth.uid() or (p_channel_id is not null and channel_id = p_channel_id and public.is_member(p_channel_id)))
  order by id desc;
$$;

-- Emergency floor override: a user with an open emergency may take the floor from someone else.
create or replace function public.request_floor(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.assert_active_user();
  f public.floor_state; c public.channels; m public.channel_members; me text;
  lease interval := interval '5 seconds';
  sos boolean;
begin
  select * into m from public.channel_members where channel_id = p_channel_id and user_id = uid;
  if not found then raise exception 'not_a_member' using errcode = '42501'; end if;
  sos := coalesce((public.setting('emergency_floor_override'))::boolean, true) and exists (
    select 1 from public.alerts where user_id = uid and kind = 'emergency' and status in ('open','acknowledged'));
  if not m.can_talk and not sos then
    return jsonb_build_object('granted', false, 'reason', 'listen_only');
  end if;
  select * into c from public.channels where id = p_channel_id;

  insert into public.floor_state(channel_id) values (p_channel_id) on conflict do nothing;
  select * into f from public.floor_state where channel_id = p_channel_id for update;

  if f.holder_id is not null and f.holder_id <> uid
     and f.lease_until > now() and f.max_until > now() and not sos then
    return jsonb_build_object('granted', false, 'reason', 'busy', 'holder_id', f.holder_id, 'holder_name', f.holder_name);
  end if;

  if f.holder_id is not null and f.holder_id <> uid then
    insert into public.connection_log(event, user_id, channel_id, detail)
    values ('floor_revoked', f.holder_id, p_channel_id,
            jsonb_build_object('reason', case when sos and f.lease_until > now() then 'emergency_override' else 'lease_expired' end));
  end if;

  select display_name into me from public.profiles where id = uid;
  update public.floor_state
     set holder_id = uid, holder_name = me,
         granted_at = case when holder_id = uid and lease_until > now() then granted_at else now() end,
         lease_until = now() + lease,
         max_until = case when holder_id = uid and lease_until > now() then max_until
                          else now() + make_interval(secs => c.max_talk_seconds) end,
         updated_at = now()
   where channel_id = p_channel_id
   returning * into f;

  insert into public.connection_log(event, user_id, channel_id) values ('floor_granted', uid, p_channel_id);
  return jsonb_build_object('granted', true, 'holder_id', uid, 'holder_name', me, 'emergency', sos,
    'lease_ms', (extract(epoch from lease) * 1000)::int,
    'max_ms', greatest(0, (extract(epoch from (f.max_until - now())) * 1000)::int));
end $$;

-- ---------------------------------------------------------------------
-- Grants & realtime
-- ---------------------------------------------------------------------
grant all on public.messages, public.user_locations, public.location_history, public.geofences,
  public.geofence_state, public.alerts to service_role;
grant all on all sequences in schema public to service_role;
grant select on public.messages, public.user_locations, public.location_history, public.geofence_state, public.alerts to authenticated;
grant select, insert, update, delete on public.geofences to authenticated;   -- RLS: admins only

revoke execute on function public.send_message(uuid,text,uuid), public.messages_since(uuid,bigint,integer),
  public.messages_before(uuid,bigint,integer), public.purge_old_messages(), public.report_location(double precision,double precision,real,real,real,smallint),
  public.purge_old_locations(), public.stop_location_sharing(), public.raise_emergency(uuid,double precision,double precision,text),
  public.update_alert(bigint,text), public.open_emergencies(uuid), public.request_floor(uuid),
  public.distance_m(double precision,double precision,double precision,double precision)
from public, anon;
grant execute on function public.send_message(uuid,text,uuid), public.messages_since(uuid,bigint,integer),
  public.messages_before(uuid,bigint,integer), public.report_location(double precision,double precision,real,real,real,smallint),
  public.stop_location_sharing(), public.raise_emergency(uuid,double precision,double precision,text),
  public.update_alert(bigint,text), public.open_emergencies(uuid), public.request_floor(uuid)
to authenticated;
grant execute on function public.purge_old_messages(), public.purge_old_locations(),
  public.distance_m(double precision,double precision,double precision,double precision) to service_role, authenticated;

do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin alter publication supabase_realtime add table public.alerts; exception when duplicate_object then null; end;
    begin alter publication supabase_realtime add table public.user_locations; exception when duplicate_object then null; end;
  end if;
end $$;
