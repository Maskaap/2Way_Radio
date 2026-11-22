-- =====================================================================
-- PTT Connect – Phase 1 database schema for Supabase (PostgreSQL 15+)
-- Run in: Supabase Dashboard -> SQL Editor -> New query -> paste -> Run
-- (or `supabase db push` if you use the Supabase CLI)
--
-- Design rules:
--  * Every table has Row Level Security (RLS) enabled.
--  * Phones never write tables directly; they call the SECURITY DEFINER
--    functions (RPCs) below, which check permissions themselves.
--  * Floor control (one talker per half-duplex channel) is an atomic
--    row lock on public.floor_state with a short lease, so a stuck button,
--    crashed app or lost network frees the channel automatically.
-- =====================================================================

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------
-- Profiles (1:1 with auth.users, created automatically on sign-up)
-- ---------------------------------------------------------------------
create table if not exists public.profiles (
  id                 uuid primary key references auth.users(id) on delete cascade,
  display_name       text not null check (char_length(display_name) between 1 and 60),
  phone              text,
  role               text not null default 'user' check (role in ('user','admin')),
  disabled           boolean not null default false,
  current_channel_id uuid,
  last_seen_at       timestamptz,
  created_at         timestamptz not null default now()
);

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, display_name, phone)
  values (
    new.id,
    coalesce(nullif(trim(new.raw_user_meta_data->>'display_name'), ''), split_part(coalesce(new.email, new.phone, 'user'), '@', 1)),
    coalesce(new.phone, new.raw_user_meta_data->>'phone')
  )
  on conflict (id) do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.profiles where id = auth.uid() and role = 'admin' and not disabled);
$$;

create or replace function public.assert_active_user()
returns uuid language plpgsql stable security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not_authenticated' using errcode = '28000'; end if;
  if exists (select 1 from public.profiles where id = uid and disabled) then
    raise exception 'account_disabled' using errcode = '42501';
  end if;
  return uid;
end $$;

-- ---------------------------------------------------------------------
-- Channels & membership
-- ---------------------------------------------------------------------
create table if not exists public.channels (
  id               uuid primary key default gen_random_uuid(),
  name             text not null check (char_length(name) between 1 and 60),
  description      text,
  kind             text not null default 'public' check (kind in ('public','private','direct')),
  created_by       uuid references public.profiles(id) on delete set null,
  max_talk_seconds integer not null default 60 check (max_talk_seconds between 5 and 600),
  archived         boolean not null default false,
  created_at       timestamptz not null default now()
);

create table if not exists public.channel_members (
  channel_id uuid not null references public.channels(id) on delete cascade,
  user_id    uuid not null references public.profiles(id) on delete cascade,
  role       text not null default 'member' check (role in ('owner','moderator','member')),
  can_talk   boolean not null default true,
  joined_at  timestamptz not null default now(),
  primary key (channel_id, user_id)
);
create index if not exists channel_members_user_idx on public.channel_members(user_id);

alter table public.profiles
  drop constraint if exists profiles_current_channel_fk;
alter table public.profiles
  add constraint profiles_current_channel_fk foreign key (current_channel_id)
  references public.channels(id) on delete set null;

create or replace function public.is_member(p_channel uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.channel_members where channel_id = p_channel and user_id = auth.uid());
$$;

create or replace function public.can_manage_channel(p_channel uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.is_admin() or exists (
    select 1 from public.channel_members
    where channel_id = p_channel and user_id = auth.uid() and role in ('owner','moderator'));
$$;

-- ---------------------------------------------------------------------
-- Floor control state (one row per channel)
-- ---------------------------------------------------------------------
create table if not exists public.floor_state (
  channel_id  uuid primary key references public.channels(id) on delete cascade,
  holder_id   uuid references public.profiles(id) on delete set null,
  holder_name text,
  granted_at  timestamptz,
  lease_until timestamptz,   -- holder must keep-alive before this
  max_until   timestamptz,   -- hard limit for one transmission
  updated_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Devices (phones) – registration and blocking
-- ---------------------------------------------------------------------
create table if not exists public.devices (
  id             uuid primary key default gen_random_uuid(),
  device_uid     text not null unique check (char_length(device_uid) between 8 and 128),
  user_id        uuid references public.profiles(id) on delete set null,
  platform       text not null check (platform in ('android','ios','web','gateway')),
  model          text,
  app_version    text,
  blocked        boolean not null default false,
  blocked_reason text,
  registered_at  timestamptz not null default now(),
  last_seen_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Connection / audit history
-- ---------------------------------------------------------------------
create table if not exists public.connection_log (
  id         bigserial primary key,
  event      text not null,
  user_id    uuid references public.profiles(id) on delete set null,
  device_uid text,
  channel_id uuid references public.channels(id) on delete set null,
  detail     jsonb,
  created_at timestamptz not null default now()
);
create index if not exists connection_log_created_idx on public.connection_log(created_at desc);
create index if not exists connection_log_user_idx on public.connection_log(user_id, created_at desc);

-- ---------------------------------------------------------------------
-- Phase 3 placeholders: physical radios & RoIP gateways (registry only)
-- No read/write programming happens here – see docs/RADIO_MANAGER.md
-- ---------------------------------------------------------------------
create table if not exists public.radio_devices (
  id            uuid primary key default gen_random_uuid(),
  brand         text not null,
  model         text not null,
  serial_number text,
  radio_id      text,                -- DMR ID / unit ID where applicable
  kind          text not null default 'handheld' check (kind in ('handheld','mobile','base','repeater','gateway','other')),
  link          text not null default 'none' check (link in ('none','usb','bluetooth','network','serial')),
  owner_user_id uuid references public.profiles(id) on delete set null,
  capabilities  jsonb not null default '{}'::jsonb,  -- filled by a verified driver, never guessed
  notes         text,
  registered_by uuid references public.profiles(id) on delete set null,
  created_at    timestamptz not null default now()
);

create table if not exists public.gateways (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  adapter           text not null,     -- e.g. 'generic-roip-audio', vendor adapter id
  linked_channel_id uuid references public.channels(id) on delete set null,
  config            jsonb not null default '{}'::jsonb,
  enabled           boolean not null default false,
  last_seen_at      timestamptz,
  created_at        timestamptz not null default now()
);

-- =====================================================================
-- Row Level Security
-- =====================================================================
alter table public.profiles        enable row level security;
alter table public.channels        enable row level security;
alter table public.channel_members enable row level security;
alter table public.floor_state     enable row level security;
alter table public.devices         enable row level security;
alter table public.connection_log  enable row level security;
alter table public.radio_devices   enable row level security;
alter table public.gateways        enable row level security;

-- Profiles: you see your own row; admins see all. Other users' names reach the apps only
-- through channel_roster()/find_users(), which never expose phone numbers or e-mails.
drop policy if exists profiles_read on public.profiles;
create policy profiles_read on public.profiles for select to authenticated using (id = auth.uid() or public.is_admin());
drop policy if exists profiles_admin_write on public.profiles;
create policy profiles_admin_write on public.profiles for update to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- Channels: public ones are visible to everyone signed in; others to members; all to admins.
drop policy if exists channels_read on public.channels;
create policy channels_read on public.channels for select to authenticated
  using (kind = 'public' or public.is_member(id) or public.is_admin());
drop policy if exists channels_manage on public.channels;
create policy channels_manage on public.channels for update to authenticated
  using (public.can_manage_channel(id)) with check (public.can_manage_channel(id));

drop policy if exists members_read on public.channel_members;
create policy members_read on public.channel_members for select to authenticated
  using (public.is_member(channel_id) or public.is_admin()
         or exists (select 1 from public.channels c where c.id = channel_id and c.kind = 'public'));
drop policy if exists members_admin_update on public.channel_members;
create policy members_admin_update on public.channel_members for update to authenticated
  using (public.can_manage_channel(channel_id)) with check (public.can_manage_channel(channel_id));

drop policy if exists floor_read on public.floor_state;
create policy floor_read on public.floor_state for select to authenticated
  using (public.is_member(channel_id) or public.is_admin());

drop policy if exists devices_read on public.devices;
create policy devices_read on public.devices for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
drop policy if exists devices_admin_update on public.devices;
create policy devices_admin_update on public.devices for update to authenticated
  using (public.is_admin()) with check (public.is_admin());

drop policy if exists log_admin_read on public.connection_log;
create policy log_admin_read on public.connection_log for select to authenticated using (public.is_admin());

drop policy if exists radios_admin on public.radio_devices;
create policy radios_admin on public.radio_devices for all to authenticated
  using (public.is_admin()) with check (public.is_admin());
drop policy if exists gateways_admin on public.gateways;
create policy gateways_admin on public.gateways for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- =====================================================================
-- RPC functions called by the apps
-- =====================================================================

-- Register / refresh this phone. Returns {blocked, reason}.
create or replace function public.register_device(
  p_device_uid text, p_platform text, p_model text default null, p_app_version text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); d public.devices;
begin
  insert into public.devices (device_uid, user_id, platform, model, app_version)
  values (p_device_uid, uid, p_platform, p_model, p_app_version)
  on conflict (device_uid) do update
    set user_id = excluded.user_id, platform = excluded.platform, model = excluded.model,
        app_version = excluded.app_version, last_seen_at = now()
  returning * into d;
  if d.blocked then
    insert into public.connection_log(event, user_id, device_uid, detail)
    values ('blocked_device_attempt', uid, p_device_uid, jsonb_build_object('reason', d.blocked_reason));
  else
    insert into public.connection_log(event, user_id, device_uid) values ('connect', uid, p_device_uid);
  end if;
  return jsonb_build_object('device_id', d.id, 'blocked', d.blocked, 'reason', d.blocked_reason);
end $$;

-- Channels visible to me, with membership info and current talker.
create or replace function public.my_channels()
returns table (id uuid, name text, description text, kind text, max_talk_seconds integer,
               is_member boolean, my_role text, can_talk boolean, member_count bigint, online_count bigint)
language sql stable security definer set search_path = public as $$
  select c.id, c.name, c.description, c.kind, c.max_talk_seconds,
         m.user_id is not null, m.role, coalesce(m.can_talk, false),
         (select count(*) from public.channel_members x where x.channel_id = c.id),
         (select count(*) from public.profiles p
            where p.current_channel_id = c.id and p.last_seen_at > now() - interval '90 seconds')
  from public.channels c
  left join public.channel_members m on m.channel_id = c.id and m.user_id = auth.uid()
  where not c.archived
    and (c.kind = 'public' or m.user_id is not null)
  order by (c.kind = 'direct'), lower(c.name);
$$;

create or replace function public.create_channel(p_name text, p_kind text default 'public', p_description text default null)
returns uuid language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); cid uuid;
begin
  if p_kind not in ('public','private') then raise exception 'invalid_kind'; end if;
  insert into public.channels(name, kind, description, created_by)
  values (trim(p_name), p_kind, p_description, uid) returning id into cid;
  insert into public.channel_members(channel_id, user_id, role) values (cid, uid, 'owner');
  insert into public.floor_state(channel_id) values (cid);
  return cid;
end $$;

-- Switch into a channel (joins public channels automatically).
create or replace function public.enter_channel(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); c public.channels; m public.channel_members;
begin
  select * into c from public.channels where id = p_channel_id and not archived;
  if not found then raise exception 'channel_not_found'; end if;
  select * into m from public.channel_members where channel_id = p_channel_id and user_id = uid;
  if not found then
    if c.kind <> 'public' then raise exception 'not_a_member' using errcode = '42501'; end if;
    insert into public.channel_members(channel_id, user_id) values (p_channel_id, uid) returning * into m;
  end if;
  update public.profiles set current_channel_id = p_channel_id, last_seen_at = now() where id = uid;
  insert into public.connection_log(event, user_id, channel_id) values ('join_channel', uid, p_channel_id);
  return jsonb_build_object('channel_id', c.id, 'name', c.name, 'kind', c.kind,
                            'can_talk', m.can_talk, 'max_talk_seconds', c.max_talk_seconds);
end $$;

create or replace function public.exit_channel(p_channel_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null then return; end if;
  update public.floor_state set holder_id = null, holder_name = null, granted_at = null,
         lease_until = null, max_until = null, updated_at = now()
   where channel_id = p_channel_id and holder_id = uid;
  update public.profiles set current_channel_id = null where id = uid and current_channel_id = p_channel_id;
  insert into public.connection_log(event, user_id, channel_id) values ('leave_channel', uid, p_channel_id);
end $$;

-- Heartbeat every ~30 s while the app is connected: drives "online" status.
create or replace function public.heartbeat(p_channel_id uuid default null)
returns void language sql security definer set search_path = public as $$
  update public.profiles set last_seen_at = now(),
         current_channel_id = coalesce(p_channel_id, current_channel_id)
   where id = auth.uid();
$$;

create or replace function public.channel_roster(p_channel_id uuid)
returns table (user_id uuid, display_name text, role text, can_talk boolean, online boolean)
language plpgsql stable security definer set search_path = public as $$
begin
  if not (public.is_member(p_channel_id) or public.is_admin()
          or exists (select 1 from public.channels where id = p_channel_id and kind = 'public')) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  return query
    select p.id, p.display_name, m.role, m.can_talk,
           (p.current_channel_id = p_channel_id and p.last_seen_at > now() - interval '90 seconds')
    from public.channel_members m join public.profiles p on p.id = m.user_id
    where m.channel_id = p_channel_id
    order by 5 desc, lower(p.display_name);
end $$;

-- Find users to add to a channel / call privately (by exact email, phone or name prefix).
create or replace function public.find_users(p_query text)
returns table (user_id uuid, display_name text)
language sql stable security definer set search_path = public, auth as $$
  select p.id, p.display_name
  from public.profiles p join auth.users u on u.id = p.id
  where auth.uid() is not null and not p.disabled and p.id <> auth.uid()
    and (lower(u.email) = lower(trim(p_query)) or p.phone = trim(p_query)
         or u.phone = trim(p_query) or p.display_name ilike trim(p_query) || '%')
  limit 20;
$$;

create or replace function public.add_member(p_channel_id uuid, p_user_id uuid, p_role text default 'member')
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.assert_active_user();
  if not public.can_manage_channel(p_channel_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  if p_role not in ('member','moderator') then raise exception 'invalid_role'; end if;
  insert into public.channel_members(channel_id, user_id, role) values (p_channel_id, p_user_id, p_role)
  on conflict (channel_id, user_id) do update set role = excluded.role;
end $$;

create or replace function public.remove_member(p_channel_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user();
begin
  if p_user_id <> uid and not public.can_manage_channel(p_channel_id) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  if exists (select 1 from public.channel_members where channel_id = p_channel_id and user_id = p_user_id and role = 'owner')
     and not public.is_admin() then
    raise exception 'cannot_remove_owner';
  end if;
  delete from public.channel_members where channel_id = p_channel_id and user_id = p_user_id;
  update public.floor_state set holder_id = null, holder_name = null, lease_until = null, max_until = null, updated_at = now()
   where channel_id = p_channel_id and holder_id = p_user_id;
end $$;

-- Private (1:1) call = a 'direct' channel with exactly two members. Re-used if it exists.
create or replace function public.open_direct_call(p_other_user uuid)
returns uuid language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); cid uuid; a text; b text;
begin
  if p_other_user = uid then raise exception 'cannot_call_self'; end if;
  select c.id into cid from public.channels c
   where c.kind = 'direct'
     and exists (select 1 from public.channel_members where channel_id = c.id and user_id = uid)
     and exists (select 1 from public.channel_members where channel_id = c.id and user_id = p_other_user)
   limit 1;
  if cid is not null then return cid; end if;
  select display_name into a from public.profiles where id = uid;
  select display_name into b from public.profiles where id = p_other_user and not disabled;
  if b is null then raise exception 'user_not_found'; end if;
  insert into public.channels(name, kind, created_by) values (left(a || ' ↔ ' || b, 60), 'direct', uid) returning id into cid;
  insert into public.channel_members(channel_id, user_id, role) values (cid, uid, 'owner'), (cid, p_other_user, 'owner');
  insert into public.floor_state(channel_id) values (cid);
  return cid;
end $$;

-- ---------------------------------------------------------------------
-- FLOOR CONTROL
-- request_floor: atomic. Grants if free, expired, or already mine.
-- Returns {granted, holder_id, holder_name, lease_ms, max_ms}
-- ---------------------------------------------------------------------
create or replace function public.request_floor(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.assert_active_user();
  f public.floor_state; c public.channels; m public.channel_members; me text;
  lease interval := interval '5 seconds';
begin
  select * into m from public.channel_members where channel_id = p_channel_id and user_id = uid;
  if not found then raise exception 'not_a_member' using errcode = '42501'; end if;
  if not m.can_talk then
    return jsonb_build_object('granted', false, 'reason', 'listen_only');
  end if;
  select * into c from public.channels where id = p_channel_id;

  insert into public.floor_state(channel_id) values (p_channel_id) on conflict do nothing;
  select * into f from public.floor_state where channel_id = p_channel_id for update;

  if f.holder_id is not null and f.holder_id <> uid
     and f.lease_until > now() and f.max_until > now() then
    return jsonb_build_object('granted', false, 'reason', 'busy',
                              'holder_id', f.holder_id, 'holder_name', f.holder_name);
  end if;

  if f.holder_id is not null and f.holder_id <> uid then
    insert into public.connection_log(event, user_id, channel_id, detail)
    values ('floor_revoked', f.holder_id, p_channel_id, jsonb_build_object('reason', 'lease_expired'));
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
  return jsonb_build_object('granted', true, 'holder_id', uid, 'holder_name', me,
    'lease_ms', (extract(epoch from lease) * 1000)::int,
    'max_ms', greatest(0, (extract(epoch from (f.max_until - now())) * 1000)::int));
end $$;

-- Keep-alive while transmitting. Returns {ok:false} if the floor was lost (max time / revoked).
create or replace function public.keepalive_floor(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid(); f public.floor_state;
begin
  update public.floor_state set lease_until = least(now() + interval '5 seconds', max_until), updated_at = now()
   where channel_id = p_channel_id and holder_id = uid and lease_until > now() and max_until > now()
   returning * into f;
  if not found then
    return jsonb_build_object('ok', false, 'reason', 'floor_lost');
  end if;
  return jsonb_build_object('ok', true, 'max_ms', greatest(0, (extract(epoch from (f.max_until - now())) * 1000)::int));
end $$;

create or replace function public.release_floor(p_channel_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  update public.floor_state set holder_id = null, holder_name = null, granted_at = null,
         lease_until = null, max_until = null, updated_at = now()
   where channel_id = p_channel_id and holder_id = uid;
  if found then
    insert into public.connection_log(event, user_id, channel_id) values ('floor_released', uid, p_channel_id);
  end if;
end $$;

-- Used by the Vercel webhook (service role) to decide whether to mute a publisher.
create or replace function public.floor_holder(p_channel_id uuid)
returns uuid language sql stable security definer set search_path = public as $$
  select holder_id from public.floor_state
   where channel_id = p_channel_id and lease_until > now() and max_until > now();
$$;

-- Clears expired floors. Optional: schedule with pg_cron (see supabase/README.md).
create or replace function public.expire_floors()
returns integer language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  with x as (
    update public.floor_state
       set holder_id = null, holder_name = null, granted_at = null, lease_until = null, max_until = null, updated_at = now()
     where holder_id is not null and (lease_until <= now() or max_until <= now())
     returning channel_id)
  select count(*) into n from x;
  return n;
end $$;

-- ---------------------------------------------------------------------
-- Admin RPCs
-- ---------------------------------------------------------------------
create or replace function public.admin_set_device_blocked(p_device_id uuid, p_blocked boolean, p_reason text default null)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  update public.devices set blocked = p_blocked, blocked_reason = case when p_blocked then p_reason end where id = p_device_id;
end $$;

create or replace function public.admin_set_user(p_user_id uuid, p_role text, p_disabled boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  if p_user_id = auth.uid() and (p_disabled or p_role <> 'admin') then raise exception 'cannot_demote_self'; end if;
  update public.profiles set role = p_role, disabled = p_disabled where id = p_user_id;
  if p_disabled then
    update public.floor_state set holder_id = null, holder_name = null, lease_until = null, max_until = null, updated_at = now()
     where holder_id = p_user_id;
  end if;
end $$;

create or replace function public.admin_set_can_talk(p_channel_id uuid, p_user_id uuid, p_can_talk boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.can_manage_channel(p_channel_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  update public.channel_members set can_talk = p_can_talk where channel_id = p_channel_id and user_id = p_user_id;
end $$;

create or replace function public.admin_active_users()
returns table (user_id uuid, display_name text, channel_name text, last_seen_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  return query
    select p.id, p.display_name, c.name, p.last_seen_at
    from public.profiles p left join public.channels c on c.id = p.current_channel_id
    where p.last_seen_at > now() - interval '90 seconds'
    order by p.last_seen_at desc;
end $$;

-- ---------------------------------------------------------------------
-- Grants: anonymous users get nothing; signed-in users may call the RPCs.
-- ---------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon, authenticated;
-- helpers used inside RLS policies must be executable by the querying role
grant execute on function public.is_admin(), public.is_member(uuid), public.can_manage_channel(uuid) to authenticated;
grant execute on all functions in schema public to service_role;
grant execute on function
  public.register_device(text,text,text,text), public.my_channels(), public.create_channel(text,text,text),
  public.enter_channel(uuid), public.exit_channel(uuid), public.heartbeat(uuid), public.channel_roster(uuid),
  public.find_users(text), public.add_member(uuid,uuid,text), public.remove_member(uuid,uuid),
  public.open_direct_call(uuid), public.request_floor(uuid), public.keepalive_floor(uuid),
  public.release_floor(uuid), public.admin_set_device_blocked(uuid,boolean,text),
  public.admin_set_user(uuid,text,boolean), public.admin_set_can_talk(uuid,uuid,boolean),
  public.admin_active_users()
to authenticated;
grant execute on function public.floor_holder(uuid), public.expire_floors() to service_role;

-- Realtime: lets the admin page (and future clients) watch floor changes live.
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin
      alter publication supabase_realtime add table public.floor_state;
    exception when duplicate_object then null;
    end;
  end if;
end $$;
