\set ON_ERROR_STOP 1
\pset footer off
insert into auth.users(id,email,raw_user_meta_data) values
 ('11111111-1111-1111-1111-111111111111','anna@x.co','{"display_name":"Anna"}'),
 ('22222222-2222-2222-2222-222222222222','ben@x.co','{"display_name":"Ben"}'),
 ('33333333-3333-3333-3333-333333333333','cara@x.co','{"display_name":"Cara"}'),
 ('44444444-4444-4444-4444-444444444444','dan@x.co','{"display_name":"Dan"}');
update public.profiles set role='admin' where id='33333333-3333-3333-3333-333333333333';
create table t(k text primary key, v text); grant all on t to authenticated, service_role;

\echo '--- 1. New user is on 14-day trial with Basic limits'
set role authenticated; select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select my_subscription()->>'source' as source, my_subscription()->>'plan_code' as plan, my_subscription()->>'has_access' as access;
select create_channel('A1') is not null as c1, create_channel('A2') is not null as c2, create_channel('A3') is not null as c3;
do $$ begin perform create_channel('A4'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice '4th channel on Basic → %', sqlerrm; end $$;

\echo '--- 2. App user cannot touch payment functions'
do $$ begin perform billing_apply_itn(gen_random_uuid(),'x','COMPLETE',1,null,null); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'user calling billing_apply_itn → %', sqlerrm; end $$;

\echo '--- 3. Checkout (server, service role): Pro monthly = R200; Business is not for sale online'
reset role; set role service_role;
insert into t select 'pay1', billing_create_checkout('11111111-1111-1111-1111-111111111111','pro','monthly')->>'payment_id';
select amount_cents, status from payments where id = (select v::uuid from t where k='pay1');
do $$ begin perform billing_create_checkout('11111111-1111-1111-1111-111111111111','business','monthly'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'Business checkout → %', sqlerrm; end $$;

\echo '--- 4. PayFast confirms payment → Pro active for 1 month'
select billing_apply_itn((select v::uuid from t where k='pay1'), 'PF1001', 'COMPLETE', 20000, 'tok-anna', '{}')->>'result' as itn1;
select plan_code, period, status, (current_period_end::date - now()::date) between 28 and 31 as about_one_month from subscriptions;
\echo '--- 5. Same notification twice → ignored'
select billing_apply_itn((select v::uuid from t where k='pay1'), 'PF1001', 'COMPLETE', 20000, 'tok-anna', '{}')->>'result' as itn_dup;
\echo '--- 6. Next month recurring charge (matched by token) → extended to ~2 months'
select billing_apply_itn(gen_random_uuid(), 'PF1002', 'COMPLETE', 20000, 'tok-anna', '{}')->>'result' as itn_recurring;
select (current_period_end::date - now()::date) between 58 and 62 as about_two_months, (select count(*) from payments where status='complete') as paid_count from subscriptions;
\echo '--- 7. Tampered amount is refused'
insert into t select 'pay2', billing_create_checkout('22222222-2222-2222-2222-222222222222','basic','yearly')->>'payment_id';
select billing_apply_itn((select v::uuid from t where k='pay2'), 'PF2001', 'COMPLETE', 100, null, '{}') as itn_tampered;
select count(*) as ben_subscriptions from subscriptions where user_id='22222222-2222-2222-2222-222222222222';
\echo '--- 8. Yearly Basic = R1000 → active ~1 year'
insert into t select 'pay3', billing_create_checkout('22222222-2222-2222-2222-222222222222','basic','yearly')->>'payment_id';
select billing_apply_itn((select v::uuid from t where k='pay3'), 'PF2002', 'COMPLETE', 100000, 'tok-ben', '{}')->>'result' as itn_yearly;
select (current_period_end::date - now()::date) between 364 and 366 as about_one_year from subscriptions where user_id='22222222-2222-2222-2222-222222222222';

\echo '--- 9. Pro user can now have more than 3 channels'
reset role; set role authenticated; select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select create_channel('A4') is not null as fourth_channel_ok;

\echo '--- 10. Cancel → keeps access until end of paid period'
reset role; set role service_role;
select billing_apply_itn(null, 'PF1001', 'CANCELLED', 0, 'tok-anna', '{}')->>'result' as itn_cancel_reusing_pf_id;
select access_for('11111111-1111-1111-1111-111111111111')->>'status' as status, access_for('11111111-1111-1111-1111-111111111111')->>'has_access' as still_access;

\echo '--- 11. Paid period over + trial over + subscription required → blocked'
reset role;
update subscriptions set current_period_end = now() - interval '10 days' where user_id='11111111-1111-1111-1111-111111111111';
update profiles set created_at = now() - interval '30 days';
update app_settings set value='true' where key='require_subscription';
set role authenticated; select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select my_subscription()->>'has_access' as anna_access, my_subscription()->>'reason' as reason;
do $$ begin perform enter_channel((select id from channels where name='A1')); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'expired user enters channel → %', sqlerrm; end $$;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select my_subscription()->>'has_access' as admin_always_has_access;

\echo '--- 12. Admin changes prices in the database; normal user cannot'
update plans set monthly_price_cents = 17500 where code='basic';
select select_ok from (select monthly_price_cents = 17500 as select_ok from plans where code='basic') x;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
update plans set monthly_price_cents = 1 where code='basic';
select monthly_price_cents as basic_after_user_tried from plans where code='basic';
reset role; set role service_role;
select billing_create_checkout('44444444-4444-4444-4444-444444444444','basic','monthly')->>'amount_cents' as new_checkout_uses_new_price;

\echo '--- 13. Admin gives a manual Business plan (e.g. paid by EFT)'
reset role; set role authenticated; select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select admin_set_subscription('44444444-4444-4444-4444-444444444444','business','yearly', now() + interval '1 year', 500000);
reset role; set role service_role;
select access_for('44444444-4444-4444-4444-444444444444')->>'plan_code' as dan_plan, access_for('44444444-4444-4444-4444-444444444444')->>'provider' as provider;

\echo '--- 14. Member limit of owner plan (Ben: Basic, limit set to 2 for test)'
reset role; update plans set max_members_per_channel = 2 where code='basic';
set role authenticated; select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
insert into t select 'bch', create_channel('Ben public');
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',false) \g /dev/null
select enter_channel((select v::uuid from t where k='bch'))->>'name' as dan_joined;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
do $$ begin perform enter_channel((select v::uuid from t where k='bch')); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice '3rd member on 2-user plan → %', sqlerrm; end $$;
\echo '=== ALL BILLING CHECKS RAN ==='
