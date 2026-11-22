-- =====================================================================
-- PTT Connect – Billing: plans, subscriptions, payments (PayFast)
-- Run AFTER 20261122000001_ptt_connect_init.sql. Safe to run again:
-- it never overwrites prices you have changed.
--
-- Change prices any time in: admin page → Plans, or Supabase → Table Editor → plans
-- Prices are stored in CENTS (R150.00 = 15000).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Plans (editable price list)
-- ---------------------------------------------------------------------
create table if not exists public.plans (
  code                    text primary key check (code ~ '^[a-z0-9_-]{2,30}$'),
  name                    text not null,
  description             text,
  monthly_price_cents     integer check (monthly_price_cents >= 0),  -- null = not sold monthly
  yearly_price_cents      integer check (yearly_price_cents >= 0),   -- null = not sold yearly
  currency                text not null default 'ZAR',
  is_custom               boolean not null default false,            -- "Contact us" unless a price is set
  max_channels            integer check (max_channels >= 0),         -- null = unlimited
  max_members_per_channel integer check (max_members_per_channel >= 0),
  features                jsonb not null default '[]'::jsonb,        -- list of selling points shown in app
  active                  boolean not null default true,
  sort_order              integer not null default 0,
  updated_at              timestamptz not null default now()
);

insert into public.plans (code, name, description, monthly_price_cents, yearly_price_cents, is_custom,
                          max_channels, max_members_per_channel, features, sort_order) values
  ('basic', 'Basic', 'For small teams getting started', 15000, 100000, false, 3, 10,
   '["Up to 3 channels","Up to 10 users per channel","Group & private calls","Volume-key push-to-talk","Wi-Fi, 4G and 5G"]', 10),
  ('pro', 'Pro', 'For busy teams that need more channels', 20000, 150000, false, 10, 50,
   '["Up to 10 channels","Up to 50 users per channel","Everything in Basic","Listen-only members","Priority support"]', 20),
  ('business', 'Business', 'Unlimited, with custom pricing and radio integration', null, null, true, null, null,
   '["Unlimited channels and users","Everything in Pro","Radio gateway integration","Dedicated support","Custom pricing – contact us"]', 30)
on conflict (code) do nothing;

-- ---------------------------------------------------------------------
-- App settings (editable)
-- ---------------------------------------------------------------------
create table if not exists public.app_settings (
  key         text primary key,
  value       jsonb not null,
  description text,
  updated_at  timestamptz not null default now()
);

insert into public.app_settings (key, value, description) values
  ('require_subscription', 'false', 'true = users need an active plan or trial to use channels. Keep false while testing.'),
  ('trial_days',           '14',    'Free trial length for new accounts (0 = no trial).'),
  ('trial_plan',           '"basic"', 'Which plan''s limits apply during the trial.'),
  ('grace_days',           '3',     'Days of access after a payment is due before access stops.'),
  ('sales_email',          '"martinhiskia@outlook.com"', 'Where "Contact us" for Business goes.'),
  ('company_name',         '"Infinity H Group"', 'Shown on invoices / payment page.')
on conflict (key) do nothing;

create or replace function public.setting(p_key text)
returns jsonb language sql stable security definer set search_path = public as $$
  select value from public.app_settings where key = p_key;
$$;

-- ---------------------------------------------------------------------
-- Subscriptions (one current subscription per user)
-- ---------------------------------------------------------------------
create table if not exists public.subscriptions (
  id                   uuid primary key default gen_random_uuid(),
  user_id              uuid not null unique references public.profiles(id) on delete cascade,
  plan_code            text not null references public.plans(code),
  period               text not null check (period in ('monthly','yearly')),
  status               text not null check (status in ('active','cancelled','expired','past_due')),
  amount_cents         integer not null default 0,
  current_period_end   timestamptz not null,
  provider             text not null default 'payfast' check (provider in ('payfast','manual')),
  provider_token       text unique,                 -- PayFast subscription token
  cancel_at_period_end boolean not null default false,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Payments (every checkout + every recurring charge)
-- ---------------------------------------------------------------------
create table if not exists public.payments (
  id                  uuid primary key default gen_random_uuid(),  -- sent to PayFast as m_payment_id
  user_id             uuid references public.profiles(id) on delete set null,
  plan_code           text references public.plans(code),
  period              text check (period in ('monthly','yearly')),
  amount_cents        integer not null,
  status              text not null default 'pending'
                        check (status in ('pending','complete','failed','cancelled','amount_mismatch')),
  provider_payment_id text unique,                                  -- PayFast pf_payment_id
  parent_payment_id   uuid references public.payments(id),          -- recurring charges point to the first payment
  raw                 jsonb,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
create index if not exists payments_user_idx on public.payments(user_id, created_at desc);

-- ---------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------
alter table public.plans         enable row level security;
alter table public.app_settings  enable row level security;
alter table public.subscriptions enable row level security;
alter table public.payments      enable row level security;

drop policy if exists plans_read on public.plans;
create policy plans_read on public.plans for select to authenticated using (active or public.is_admin());
drop policy if exists plans_admin on public.plans;
create policy plans_admin on public.plans for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists settings_read on public.app_settings;
create policy settings_read on public.app_settings for select to authenticated using (true);
drop policy if exists settings_admin on public.app_settings;
create policy settings_admin on public.app_settings for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists subs_read on public.subscriptions;
create policy subs_read on public.subscriptions for select to authenticated using (user_id = auth.uid() or public.is_admin());

drop policy if exists payments_read on public.payments;
create policy payments_read on public.payments for select to authenticated using (user_id = auth.uid() or public.is_admin());

-- ---------------------------------------------------------------------
-- Access logic
-- ---------------------------------------------------------------------
-- What plan applies to a user right now, and may they use the service?
create or replace function public.access_for(p_user uuid)
returns jsonb language plpgsql stable security definer set search_path = public as $$
declare
  p public.profiles; s public.subscriptions; pl public.plans;
  trial_days int := coalesce((public.setting('trial_days'))::int, 0);
  grace interval := make_interval(days => coalesce((public.setting('grace_days'))::int, 0));
  required boolean := coalesce((public.setting('require_subscription'))::boolean, false);
  trial_ends timestamptz;
begin
  select * into p from public.profiles where id = p_user;
  if not found then return jsonb_build_object('has_access', false, 'reason', 'no_profile'); end if;
  if p.role = 'admin' then
    return jsonb_build_object('has_access', true, 'source', 'admin', 'plan_code', null);
  end if;

  select * into s from public.subscriptions where user_id = p_user;
  if found and s.status in ('active','cancelled','past_due') and s.current_period_end + grace > now() then
    select * into pl from public.plans where code = s.plan_code;
    return jsonb_build_object('has_access', true, 'source', 'subscription', 'plan_code', s.plan_code,
      'plan_name', pl.name, 'period', s.period, 'status', s.status, 'ends_at', s.current_period_end,
      'cancel_at_period_end', s.cancel_at_period_end, 'provider', s.provider);
  end if;

  trial_ends := p.created_at + make_interval(days => trial_days);
  if trial_days > 0 and trial_ends > now() then
    select * into pl from public.plans where code = (public.setting('trial_plan') #>> '{}');
    return jsonb_build_object('has_access', true, 'source', 'trial', 'plan_code', pl.code,
      'plan_name', pl.name, 'ends_at', trial_ends);
  end if;

  return jsonb_build_object('has_access', not required, 'source', 'none', 'plan_code', null,
    'reason', case when required then 'subscription_required' end,
    'ended_at', coalesce(s.current_period_end, trial_ends));
end $$;

create or replace function public.my_subscription()
returns jsonb language sql stable security definer set search_path = public as $$
  select public.access_for(auth.uid()) || jsonb_build_object(
    'sales_email', public.setting('sales_email') #>> '{}',
    'require_subscription', public.setting('require_subscription'));
$$;

-- Limits of the plan that applies to p_user (nulls = unlimited).
create or replace function public.limits_for(p_user uuid, out max_channels integer, out max_members integer)
language plpgsql stable security definer set search_path = public as $$
declare a jsonb := public.access_for(p_user);
begin
  if a->>'plan_code' is null then return; end if;   -- admin / no plan in test mode → unlimited
  select pl.max_channels, pl.max_members_per_channel into max_channels, max_members
    from public.plans pl where pl.code = a->>'plan_code';
end $$;

create or replace function public.assert_access(p_user uuid)
returns void language plpgsql stable security definer set search_path = public as $$
begin
  if not coalesce((public.access_for(p_user)->>'has_access')::boolean, false) then
    raise exception 'subscription_required' using errcode = '42501';
  end if;
end $$;

-- Room for one more member in this channel? (limit comes from the channel OWNER's plan)
create or replace function public.assert_member_capacity(p_channel uuid)
returns void language plpgsql stable security definer set search_path = public as $$
declare owner uuid; lim integer; n integer;
begin
  select created_by into owner from public.channels where id = p_channel;
  if owner is null then return; end if;
  select max_members into lim from public.limits_for(owner);
  if lim is null then return; end if;
  select count(*) into n from public.channel_members where channel_id = p_channel;
  if n >= lim then raise exception 'plan_limit_members'; end if;
end $$;

-- ---------------------------------------------------------------------
-- Existing functions, now plan-aware (same signatures as before)
-- ---------------------------------------------------------------------
create or replace function public.create_channel(p_name text, p_kind text default 'public', p_description text default null)
returns uuid language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); cid uuid; lim integer; n integer;
begin
  perform public.assert_access(uid);
  if p_kind not in ('public','private') then raise exception 'invalid_kind'; end if;
  select max_channels into lim from public.limits_for(uid);
  if lim is not null then
    select count(*) into n from public.channels where created_by = uid and kind <> 'direct' and not archived;
    if n >= lim then raise exception 'plan_limit_channels'; end if;
  end if;
  insert into public.channels(name, kind, description, created_by)
  values (trim(p_name), p_kind, p_description, uid) returning id into cid;
  insert into public.channel_members(channel_id, user_id, role) values (cid, uid, 'owner');
  insert into public.floor_state(channel_id) values (cid);
  return cid;
end $$;

create or replace function public.enter_channel(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); c public.channels; m public.channel_members;
begin
  perform public.assert_access(uid);
  select * into c from public.channels where id = p_channel_id and not archived;
  if not found then raise exception 'channel_not_found'; end if;
  select * into m from public.channel_members where channel_id = p_channel_id and user_id = uid;
  if not found then
    if c.kind <> 'public' then raise exception 'not_a_member' using errcode = '42501'; end if;
    perform public.assert_member_capacity(p_channel_id);
    insert into public.channel_members(channel_id, user_id) values (p_channel_id, uid) returning * into m;
  end if;
  update public.profiles set current_channel_id = p_channel_id, last_seen_at = now() where id = uid;
  insert into public.connection_log(event, user_id, channel_id) values ('join_channel', uid, p_channel_id);
  return jsonb_build_object('channel_id', c.id, 'name', c.name, 'kind', c.kind,
                            'can_talk', m.can_talk, 'max_talk_seconds', c.max_talk_seconds);
end $$;

create or replace function public.add_member(p_channel_id uuid, p_user_id uuid, p_role text default 'member')
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.assert_active_user();
  if not public.can_manage_channel(p_channel_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  if p_role not in ('member','moderator') then raise exception 'invalid_role'; end if;
  if not exists (select 1 from public.channel_members where channel_id = p_channel_id and user_id = p_user_id) then
    perform public.assert_member_capacity(p_channel_id);
  end if;
  insert into public.channel_members(channel_id, user_id, role) values (p_channel_id, p_user_id, p_role)
  on conflict (channel_id, user_id) do update set role = excluded.role;
end $$;

-- ---------------------------------------------------------------------
-- Payment processing (called ONLY by the Vercel server with the secret key)
-- ---------------------------------------------------------------------
create or replace function public.billing_create_checkout(p_user uuid, p_plan text, p_period text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare pl public.plans; amt integer; pid uuid; email text; nm text;
begin
  if p_period not in ('monthly','yearly') then raise exception 'invalid_period'; end if;
  select * into pl from public.plans where code = p_plan and active;
  if not found then raise exception 'plan_not_found'; end if;
  amt := case p_period when 'monthly' then pl.monthly_price_cents else pl.yearly_price_cents end;
  if amt is null or amt <= 0 then raise exception 'plan_not_for_sale'; end if;  -- e.g. Business = contact us
  if exists (select 1 from public.profiles where id = p_user and disabled) then raise exception 'account_disabled'; end if;
  insert into public.payments(user_id, plan_code, period, amount_cents) values (p_user, p_plan, p_period, amt)
  returning id into pid;
  select u.email, pr.display_name into email, nm from auth.users u join public.profiles pr on pr.id = u.id where u.id = p_user;
  return jsonb_build_object('payment_id', pid, 'amount_cents', amt, 'plan_name', pl.name, 'email', email, 'name', nm);
end $$;

-- Applies one verified PayFast notification (ITN). Idempotent per pf_payment_id.
create or replace function public.billing_apply_itn(
  p_payment_id uuid, p_pf_payment_id text, p_status text, p_amount_cents integer, p_token text, p_raw jsonb)
returns jsonb language plpgsql security definer set search_path = public as $$
declare pay public.payments; sub public.subscriptions; step interval; base timestamptz; expected integer;
begin
  if p_status = 'COMPLETE' and p_pf_payment_id is not null
     and exists (select 1 from public.payments where provider_payment_id = p_pf_payment_id) then
    return jsonb_build_object('result', 'duplicate');
  end if;

  select * into pay from public.payments where id = p_payment_id;
  if not found and p_token is not null then
    -- Recurring charge we can only match by subscription token
    select * into sub from public.subscriptions where provider_token = p_token;
    if found then
      select * into pay from public.payments
        where user_id = sub.user_id and parent_payment_id is null and status = 'complete'
        order by created_at desc limit 1;
    end if;
  end if;
  if pay.id is null then return jsonb_build_object('result', 'unknown_payment'); end if;

  if p_status = 'CANCELLED' then
    update public.subscriptions set status = 'cancelled', cancel_at_period_end = true, updated_at = now()
     where user_id = pay.user_id and (provider_token = p_token or p_token is null);
    update public.payments set status = 'cancelled', raw = p_raw, updated_at = now() where id = pay.id and status = 'pending';
    return jsonb_build_object('result', 'cancelled');
  end if;

  if p_status <> 'COMPLETE' then
    update public.payments set status = 'failed', raw = p_raw, updated_at = now() where id = pay.id and status = 'pending';
    return jsonb_build_object('result', 'not_complete', 'status', p_status);
  end if;

  select * into sub from public.subscriptions where user_id = pay.user_id;
  -- First payment must match the checkout amount; renewals must match the subscribed amount.
  expected := case when pay.status = 'pending' then pay.amount_cents else coalesce(sub.amount_cents, pay.amount_cents) end;
  if abs(p_amount_cents - expected) > 1 then
    if pay.status = 'pending' then
      update public.payments set status = 'amount_mismatch', raw = p_raw, updated_at = now() where id = pay.id;
    end if;
    return jsonb_build_object('result', 'amount_mismatch', 'expected', expected, 'got', p_amount_cents);
  end if;

  if pay.status = 'pending' then
    update public.payments set status = 'complete', provider_payment_id = p_pf_payment_id, raw = p_raw, updated_at = now()
     where id = pay.id;
  else
    insert into public.payments(user_id, plan_code, period, amount_cents, status, provider_payment_id, parent_payment_id, raw)
    values (pay.user_id, pay.plan_code, pay.period, p_amount_cents, 'complete', p_pf_payment_id, pay.id, p_raw);
  end if;

  step := case pay.period when 'monthly' then interval '1 month' else interval '1 year' end;
  base := case when sub.id is not null and sub.plan_code = pay.plan_code and sub.period = pay.period
                    and sub.current_period_end > now() then sub.current_period_end else now() end;

  insert into public.subscriptions(user_id, plan_code, period, status, amount_cents, current_period_end, provider, provider_token)
  values (pay.user_id, pay.plan_code, pay.period, 'active', expected, base + step, 'payfast', p_token)
  on conflict (user_id) do update set
    plan_code = excluded.plan_code, period = excluded.period, status = 'active',
    amount_cents = excluded.amount_cents, current_period_end = excluded.current_period_end,
    provider = 'payfast', provider_token = coalesce(excluded.provider_token, public.subscriptions.provider_token),
    cancel_at_period_end = false, updated_at = now();

  return jsonb_build_object('result', 'activated', 'until', base + step);
end $$;

-- Marks a subscription as cancelled after the PayFast API confirmed it (access continues until period end).
create or replace function public.billing_mark_cancelled(p_user uuid)
returns void language sql security definer set search_path = public as $$
  update public.subscriptions set status = 'cancelled', cancel_at_period_end = true, updated_at = now() where user_id = p_user;
$$;

-- Admin: give / change a plan by hand (Business clients, EFT, free accounts …)
create or replace function public.admin_set_subscription(p_user uuid, p_plan text, p_period text, p_ends_at timestamptz,
                                                         p_amount_cents integer default 0)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'admin_only' using errcode = '42501'; end if;
  if p_plan is null then
    delete from public.subscriptions where user_id = p_user; return;
  end if;
  insert into public.subscriptions(user_id, plan_code, period, status, amount_cents, current_period_end, provider)
  values (p_user, p_plan, p_period, 'active', coalesce(p_amount_cents, 0), p_ends_at, 'manual')
  on conflict (user_id) do update set plan_code = excluded.plan_code, period = excluded.period, status = 'active',
    amount_cents = excluded.amount_cents, current_period_end = excluded.current_period_end,
    provider = 'manual', provider_token = null, cancel_at_period_end = false, updated_at = now();
end $$;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
grant all on public.plans, public.app_settings, public.subscriptions, public.payments to service_role;
grant select on public.plans, public.app_settings, public.subscriptions, public.payments to authenticated;
grant insert, update, delete on public.plans, public.app_settings to authenticated;   -- RLS: admins only

revoke execute on function public.setting(text), public.access_for(uuid), public.limits_for(uuid),
  public.assert_access(uuid), public.assert_member_capacity(uuid), public.billing_create_checkout(uuid,text,text),
  public.billing_apply_itn(uuid,text,text,integer,text,jsonb), public.billing_mark_cancelled(uuid),
  public.my_subscription(), public.admin_set_subscription(uuid,text,text,timestamptz,integer),
  public.create_channel(text,text,text), public.enter_channel(uuid), public.add_member(uuid,uuid,text)
from public, anon;
grant execute on function public.my_subscription(), public.admin_set_subscription(uuid,text,text,timestamptz,integer),
  public.create_channel(text,text,text), public.enter_channel(uuid), public.add_member(uuid,uuid,text)
to authenticated;
grant execute on function public.access_for(uuid), public.billing_create_checkout(uuid,text,text),
  public.billing_apply_itn(uuid,text,text,integer,text,jsonb), public.billing_mark_cancelled(uuid)
to service_role;
