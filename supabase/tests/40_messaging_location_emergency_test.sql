\set ON_ERROR_STOP 1
\pset footer off
insert into auth.users(id,email,raw_user_meta_data) values
 ('11111111-1111-1111-1111-111111111111','anna@x.co','{"display_name":"Anna"}'),
 ('22222222-2222-2222-2222-222222222222','ben@x.co','{"display_name":"Ben"}'),
 ('33333333-3333-3333-3333-333333333333','cara@x.co','{"display_name":"Cara"}'),
 ('44444444-4444-4444-4444-444444444444','dan@x.co','{"display_name":"Dan"}');
update public.profiles set role='admin' where id='33333333-3333-3333-3333-333333333333';
create table t(k text primary key, v text); grant all on t to authenticated;
set role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
insert into t select 'ch', create_channel('Patrol','private');
select add_member((select v::uuid from t where k='ch'),'22222222-2222-2222-2222-222222222222') \g /dev/null

\echo '--- MESSAGING ---'
\echo '1. Anna sends 2 messages; re-sending the same client_id (offline retry) does not duplicate'
select send_message((select v::uuid from t where k='ch'), 'Gate 3 is open', 'aaaaaaaa-0000-0000-0000-000000000001')->>'body' as m1;
select send_message((select v::uuid from t where k='ch'), 'Gate 3 is open', 'aaaaaaaa-0000-0000-0000-000000000001')->>'body' as m1_retry;
select send_message((select v::uuid from t where k='ch'), 'On my way', 'aaaaaaaa-0000-0000-0000-000000000002')->>'body' as m2;
select count(*) as stored_messages from messages;
\echo '2. Ben (member) syncs everything after id 0, then only newer after the first'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select string_agg(sender_name || ': ' || body, ' | ' order by id) as ben_sync_all from messages_since((select v::uuid from t where k='ch'), 0);
select string_agg(body, ' | ') as ben_sync_newer from messages_since((select v::uuid from t where k='ch'), (select min(id) from messages));
select string_agg(body, ' | ') as history_before_last from messages_before((select v::uuid from t where k='ch'), (select max(id) from messages));
\echo '3. Dan (not a member) cannot read or send'
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',false) \g /dev/null
select count(*) as dan_sees_rows from messages;
do $$ begin perform messages_since((select v::uuid from t where k='ch'), 0); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'non-member reads history → %', sqlerrm; end $$;
do $$ begin perform send_message((select v::uuid from t where k='ch'), 'hi', null); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'non-member sends → %', sqlerrm; end $$;

\echo '--- GPS + GEOFENCE ---'
\echo '4. Admin creates a 200 m geofence around a depot'
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
insert into geofences(name, lat, lng, radius_m) values ('Depot', -26.2041, 28.0473, 200);
\echo '5. Ben reports inside (first fix, no alert), then ~1.1 km away (exit alert), then back (enter alert)'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select report_location(-26.2042, 28.0474, 8, 0, 0, 87::smallint)->'alerts' as first_fix_alerts;
select report_location(-26.2140, 28.0473, 8, 2, 180, 86::smallint)->'alerts'->0->>'entered' as left_depot_entered;
select report_location(-26.2041, 28.0472, 8, 0, 0, 85::smallint)->'alerts'->0->>'entered' as back_entered;
select string_agg(kind || ': ' || message, ' | ' order by id) as alerts from alerts;
select count(*) as trail_points_throttled from location_history;
\echo '6. Privacy: Anna cannot see Ben''s position; admin can'
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select count(*) as anna_sees_positions from user_locations;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select count(*) as admin_sees_positions, max(battery_pct) as battery from user_locations;

\echo '--- EMERGENCY ---'
\echo '7. Anna is talking; Ben presses SOS → alert + channel message; pressing again does not duplicate'
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select request_floor((select v::uuid from t where k='ch'))->>'granted' as anna_has_floor;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
insert into t select 'sos', raise_emergency((select v::uuid from t where k='ch'), -26.21, 28.05, null)->>'id';
select raise_emergency((select v::uuid from t where k='ch'), -26.211, 28.051, null)->>'repeat' as second_press_is_repeat;
select count(*) as open_emergencies from alerts where kind='emergency' and status='open';
select body as channel_message from messages where kind='emergency';
\echo '8. Emergency override: Ben may talk although Anna holds the floor'
select request_floor((select v::uuid from t where k='ch'))->>'emergency' as ben_override;
select holder_name as holder_now from floor_state where channel_id=(select v::uuid from t where k='ch');
\echo '9. Channel member sees the emergency; outsider does not'
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select count(*) as anna_sees_sos from open_emergencies((select v::uuid from t where k='ch'));
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',false) \g /dev/null
select count(*) as dan_sees_sos from open_emergencies((select v::uuid from t where k='ch'));
\echo '10. Anna (not admin) cannot resolve; admin acknowledges then resolves; system message posted'
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
do $$ begin perform update_alert((select v::bigint from t where k='sos'), 'resolved'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'member resolves SOS → %', sqlerrm; end $$;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select update_alert((select v::bigint from t where k='sos'), 'acknowledged') \g /dev/null
select update_alert((select v::bigint from t where k='sos'), 'resolved') \g /dev/null
select status, acknowledged_by is not null as ack_recorded from alerts where id=(select v::bigint from t where k='sos');
select body as last_message from messages order by id desc limit 1;
\echo '11. After resolve, Ben no longer overrides the floor'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select release_floor((select v::uuid from t where k='ch')) \g /dev/null
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select request_floor((select v::uuid from t where k='ch'))->>'granted' as anna_takes_floor_back;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select request_floor((select v::uuid from t where k='ch'))->>'reason' as ben_now;
\echo '12. Ben can cancel his own false alarm'
insert into t select 'sos2', raise_emergency(null, null, null, 'pressed by mistake')->>'id';
select update_alert((select v::bigint from t where k='sos2'), 'cancelled') \g /dev/null
select status from alerts where id=(select v::bigint from t where k='sos2');
\echo '=== ALL MESSAGING / GPS / EMERGENCY CHECKS RAN ==='
