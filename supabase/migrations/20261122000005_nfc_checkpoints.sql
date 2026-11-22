-- =====================================================================
-- PTT Connect – NFC tagging (guard patrol checkpoints)
-- Run AFTER 20261122000004_messaging_location_emergency.sql. Safe to run again.
-- =====================================================================

insert into public.app_settings (key, value, description) values
  ('nfc_post_to_channel', 'false', 'true = every checkpoint scan is also posted as a message in the guard''s channel.'),
  ('checkpoint_grace_minutes', '5', 'Extra minutes allowed before a checkpoint counts as missed.')
on conflict (key) do nothing;

create table if not exists public.nfc_tags (
  id                     uuid primary key default gen_random_uuid(),
  tag_uid                text not null unique check (tag_uid ~ '^[0-9A-F]{8,32}$'),  -- hardware ID, upper-case hex
  name                   text not null check (char_length(name) between 1 and 80),
  site                   text,                                  -- e.g. "Main depot"
  lat                    double precision check (lat between -90 and 90),
  lng                    double precision check (lng between -180 and 180),
  expected_every_minutes integer check (expected_every_minutes between 5 and 10080),  -- null = no schedule
  active                 boolean not null default true,
  created_by             uuid references public.profiles(id) on delete set null,
  created_at             timestamptz not null default now()
);

create table if not exists public.tag_scans (
  id          bigserial primary key,
  tag_id      uuid references public.nfc_tags(id) on delete set null,
  tag_uid     text not null,
  tag_name    text,                         -- copy at scan time (kept if the tag is renamed/deleted)
  user_id     uuid references public.profiles(id) on delete set null,
  user_name   text,
  channel_id  uuid references public.channels(id) on delete set null,
  lat         double precision,
  lng         double precision,
  known       boolean not null,
  scanned_at  timestamptz not null default now()
);
create index if not exists tag_scans_tag_idx on public.tag_scans(tag_id, scanned_at desc);
create index if not exists tag_scans_time_idx on public.tag_scans(scanned_at desc);

-- Alerts can now also be "checkpoint missed"
alter table public.alerts add column if not exists tag_id uuid references public.nfc_tags(id) on delete set null;
alter table public.alerts drop constraint if exists alerts_kind_check;
alter table public.alerts add constraint alerts_kind_check
  check (kind in ('emergency','geofence_enter','geofence_exit','checkpoint_missed'));

alter table public.nfc_tags  enable row level security;
alter table public.tag_scans enable row level security;
drop policy if exists tags_admin on public.nfc_tags;
create policy tags_admin on public.nfc_tags for all to authenticated using (public.is_admin()) with check (public.is_admin());
drop policy if exists scans_read on public.tag_scans;
create policy scans_read on public.tag_scans for select to authenticated using (user_id = auth.uid() or public.is_admin());

-- A phone scanned a tag. Unknown tags are logged too (so you can see unregistered tags being scanned).
create or replace function public.record_tag_scan(p_tag_uid text, p_lat double precision default null, p_lng double precision default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); nm text; ch uuid; t public.nfc_tags; s public.tag_scans;
        u text := upper(regexp_replace(coalesce(p_tag_uid, ''), '[^0-9A-Fa-f]', '', 'g'));
        recent boolean;
begin
  if length(u) < 8 then raise exception 'invalid_tag'; end if;
  select display_name, current_channel_id into nm, ch from public.profiles where id = uid;
  select * into t from public.nfc_tags where tag_uid = u and active;

  -- Same person, same tag within 30 s = one scan (phones often read a tag twice)
  select exists (select 1 from public.tag_scans where tag_uid = u and user_id = uid and scanned_at > now() - interval '30 seconds') into recent;
  if recent then
    select * into s from public.tag_scans where tag_uid = u and user_id = uid order by id desc limit 1;
    return jsonb_build_object('known', s.known, 'tag_name', s.tag_name, 'scan_id', s.id, 'duplicate', true, 'is_admin', public.is_admin());
  end if;

  insert into public.tag_scans(tag_id, tag_uid, tag_name, user_id, user_name, channel_id, lat, lng, known)
  values (t.id, u, t.name, uid, nm, ch, p_lat, p_lng, t.id is not null)
  returning * into s;

  if t.id is not null then
    -- a scan closes any open "missed" alert for this checkpoint
    update public.alerts set status = 'resolved', resolved_at = now()
     where kind = 'checkpoint_missed' and tag_id = t.id and status in ('open','acknowledged');
    if ch is not null and coalesce((public.setting('nfc_post_to_channel'))::boolean, false) then
      insert into public.messages(channel_id, sender_id, sender_name, kind, body)
      values (ch, uid, nm, 'system', '📍 ' || nm || ' checked in at ' || t.name);
    end if;
  end if;
  return jsonb_build_object('known', t.id is not null, 'tag_name', t.name, 'site', t.site, 'scan_id', s.id,
                            'duplicate', false, 'is_admin', public.is_admin(), 'tag_uid', u);
end $$;

-- Admin registers (or renames) a tag, e.g. right after scanning it with the phone.
create or replace function public.register_tag(p_tag_uid text, p_name text, p_site text default null,
  p_lat double precision default null, p_lng double precision default null, p_expected_every_minutes integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); t public.nfc_tags;
        u text := upper(regexp_replace(coalesce(p_tag_uid, ''), '[^0-9A-Fa-f]', '', 'g'));
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  if length(u) < 8 then raise exception 'invalid_tag'; end if;
  insert into public.nfc_tags(tag_uid, name, site, lat, lng, expected_every_minutes, created_by)
  values (u, trim(p_name), nullif(trim(p_site), ''), p_lat, p_lng, p_expected_every_minutes, uid)
  on conflict (tag_uid) do update set name = excluded.name, site = coalesce(excluded.site, public.nfc_tags.site),
    lat = coalesce(excluded.lat, public.nfc_tags.lat), lng = coalesce(excluded.lng, public.nfc_tags.lng),
    expected_every_minutes = coalesce(excluded.expected_every_minutes, public.nfc_tags.expected_every_minutes), active = true
  returning * into t;
  -- earlier "unknown" scans of this tag now count for it
  update public.tag_scans set tag_id = t.id, tag_name = t.name, known = true where tag_uid = u and tag_id is null;
  return to_jsonb(t);
end $$;

-- Raises one alert per overdue checkpoint (until it is scanned again). Run by pg_cron every few minutes,
-- and also each time an admin opens the Checkpoints / Alerts page.
create or replace function public.check_missed_checkpoints()
returns integer language plpgsql security definer set search_path = public as $$
declare t record; last_scan timestamptz; due timestamptz; n integer := 0;
        grace interval := make_interval(mins => coalesce((public.setting('checkpoint_grace_minutes'))::int, 5));
begin
  if auth.uid() is not null and not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  for t in select * from public.nfc_tags where active and expected_every_minutes is not null loop
    select max(scanned_at) into last_scan from public.tag_scans where tag_id = t.id;
    due := coalesce(last_scan, t.created_at) + make_interval(mins => t.expected_every_minutes) + grace;
    if now() > due and not exists (
        select 1 from public.alerts where kind = 'checkpoint_missed' and tag_id = t.id
          and created_at > coalesce(last_scan, t.created_at)) then
      insert into public.alerts(kind, tag_id, lat, lng, message)
      values ('checkpoint_missed', t.id, t.lat, t.lng,
              'Checkpoint "' || t.name || '"' || coalesce(' (' || t.site || ')', '') || ' not scanned for ' ||
              round(extract(epoch from (now() - coalesce(last_scan, t.created_at))) / 60) || ' min');
      n := n + 1;
    end if;
  end loop;
  return n;
end $$;

-- Overview for the admin page.
create or replace function public.checkpoint_status()
returns table (id uuid, tag_uid text, name text, site text, lat double precision, lng double precision,
               expected_every_minutes integer, active boolean, last_scan timestamptz, last_by text, overdue boolean)
language plpgsql stable security definer set search_path = public as $$
declare grace interval := make_interval(mins => coalesce((public.setting('checkpoint_grace_minutes'))::int, 5));
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  return query
    select t.id, t.tag_uid, t.name, t.site, t.lat, t.lng, t.expected_every_minutes, t.active, ls.scanned_at, ls.user_name,
           (t.active and t.expected_every_minutes is not null and
            now() > coalesce(ls.scanned_at, t.created_at) + make_interval(mins => t.expected_every_minutes) + grace)
    from public.nfc_tags t
    left join lateral (select s.scanned_at, s.user_name from public.tag_scans s where s.tag_id = t.id order by s.scanned_at desc limit 1) ls on true
    order by t.site nulls last, t.name;
end $$;

grant all on public.nfc_tags, public.tag_scans to service_role;
grant all on all sequences in schema public to service_role;
grant select, insert, update, delete on public.nfc_tags to authenticated;  -- RLS: admins only
grant select on public.tag_scans to authenticated;

revoke execute on function public.record_tag_scan(text,double precision,double precision),
  public.register_tag(text,text,text,double precision,double precision,integer),
  public.check_missed_checkpoints(), public.checkpoint_status() from public, anon;
grant execute on function public.record_tag_scan(text,double precision,double precision),
  public.register_tag(text,text,text,double precision,double precision,integer),
  public.check_missed_checkpoints(), public.checkpoint_status() to authenticated, service_role;
