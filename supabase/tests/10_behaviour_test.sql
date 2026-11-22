\set ON_ERROR_STOP 1
insert into auth.users(id,email,raw_user_meta_data) values
 ('11111111-1111-1111-1111-111111111111','anna@x.co','{"display_name":"Anna"}'),
 ('22222222-2222-2222-2222-222222222222','ben@x.co','{"display_name":"Ben"}'),
 ('33333333-3333-3333-3333-333333333333','cara@x.co','{"display_name":"Cara"}');
update public.profiles set role='admin' where id='33333333-3333-3333-3333-333333333333';
create temp table t(k text primary key, v text); grant all on t to authenticated;

-- ANNA
set role authenticated; select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
select public.register_device('anna-phone-0001','android','Pixel 7','0.1.0') as anna_device;
insert into t select 'pub', public.create_channel('Dispatch','public');
insert into t select 'priv', public.create_channel('Secret Ops','private');
select public.enter_channel((select v::uuid from t where k='pub')) ->> 'name' as anna_entered;
select public.request_floor((select v::uuid from t where k='pub')) as anna_req;
select public.keepalive_floor((select v::uuid from t where k='pub'))->>'ok' as anna_keepalive;

-- BEN
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select public.register_device('ben-phone-0001','android','Galaxy','0.1.0')->>'blocked' as ben_blocked;
select count(*) as ben_sees_channels, string_agg(name, ',') from public.my_channels();
select count(*) as ben_sees_priv_rows from public.channels where name='Secret Ops';
select public.enter_channel((select v::uuid from t where k='pub'))->>'can_talk' as ben_can_talk;
select public.request_floor((select v::uuid from t where k='pub')) as ben_req_busy;
do $$ begin perform public.enter_channel((select v::uuid from t where k='priv')); raise exception 'SHOULD_FAIL';
 exception when others then if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'ben blocked from private: %', sqlerrm; end $$;
do $$ begin perform public.admin_set_device_blocked(gen_random_uuid(), true); raise exception 'SHOULD_FAIL';
 exception when others then if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'ben not admin: %', sqlerrm; end $$;
do $$ begin insert into public.channels(name) values('hack'); raise exception 'SHOULD_FAIL';
 exception when others then if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'ben direct insert denied: %', sqlerrm; end $$;

-- ANNA releases, BEN gets it
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
select public.release_floor((select v::uuid from t where k='pub'));
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select public.request_floor((select v::uuid from t where k='pub'))->>'granted' as ben_granted_after_release;

-- Simulate Ben's app crashing (lease expires) -> Anna can take over
reset role; update public.floor_state set lease_until = now() - interval '1 second'; set role authenticated;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select public.keepalive_floor((select v::uuid from t where k='pub'))->>'ok' as ben_keepalive_after_expiry;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
select public.request_floor((select v::uuid from t where k='pub'))->>'granted' as anna_takes_expired_floor;
-- Max talk time reached -> keepalive fails
reset role; update public.floor_state set max_until = now() - interval '1 second'; set role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
select public.keepalive_floor((select v::uuid from t where k='pub'))->>'ok' as anna_keepalive_after_max;
select public.release_floor((select v::uuid from t where k='pub'));

-- Private channel membership + listen-only
select (public.find_users('ben@x.co')).display_name as found;
select public.add_member((select v::uuid from t where k='priv'), '22222222-2222-2222-2222-222222222222');
select public.admin_set_can_talk((select v::uuid from t where k='priv'), '22222222-2222-2222-2222-222222222222', false);
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select public.request_floor((select v::uuid from t where k='priv'))->>'reason' as ben_listen_only;
-- Direct call (reused on second call)
select public.open_direct_call('11111111-1111-1111-1111-111111111111') = public.open_direct_call('11111111-1111-1111-1111-111111111111') as direct_reused;
select user_id is not null, display_name, online from public.channel_roster((select v::uuid from t where k='pub'));

-- ADMIN (Cara) blocks Ben's phone
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false);
select public.admin_set_device_blocked((select id from public.devices where device_uid='ben-phone-0001'), true, 'lost phone');
select count(*) as admin_sees_log_rows from public.connection_log;
select display_name from public.admin_active_users();
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select public.register_device('ben-phone-0001','android','Galaxy','0.1.0') as ben_after_block;
select count(*) as ben_sees_log_rows from public.connection_log;
-- anon can't call RPCs
reset role; set role anon;
do $$ begin perform public.my_channels(); raise exception 'SHOULD_FAIL';
 exception when others then if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'anon denied: %', sqlerrm; end $$;
reset role; grant select on t to service_role; set role service_role;
select public.floor_holder((select v::uuid from t where k='pub')) as holder_now_null, public.expire_floors() as expired;
reset role; set role authenticated;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
select count(*) as ben_sees_profiles from public.profiles;
select count(*) as ben_roster_names from public.channel_roster((select v::uuid from t where k='pub'));
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false);
select count(*) as admin_sees_profiles from public.profiles;
