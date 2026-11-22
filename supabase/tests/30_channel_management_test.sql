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
insert into t select 'grp', create_channel('Security team','private');
insert into t select 'pub', create_channel('Dispatch','public');
select add_member((select v::uuid from t where k='grp'),'22222222-2222-2222-2222-222222222222');
select add_member((select v::uuid from t where k='grp'),'44444444-4444-4444-4444-444444444444');
select request_floor((select v::uuid from t where k='grp')) is not null \g /dev/null

\echo '--- 1. Member (Ben) cannot delete the group or remove others'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
do $$ begin perform delete_channel((select v::uuid from t where k='grp')); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'member deletes group → %', sqlerrm; end $$;
do $$ begin perform remove_member((select v::uuid from t where k='grp'),'44444444-4444-4444-4444-444444444444'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'member removes another → %', sqlerrm; end $$;
\echo '--- 2. Member can leave (remove self)'
select remove_member((select v::uuid from t where k='grp'),'22222222-2222-2222-2222-222222222222') is null as ben_left;
\echo '--- 3. Owner (Anna) removes Dan; cannot be removed herself by non-admin'
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false) \g /dev/null
select remove_member((select v::uuid from t where k='grp'),'44444444-4444-4444-4444-444444444444') is null as dan_removed;
select count(*) as members_left from channel_members where channel_id=(select v::uuid from t where k='grp');
do $$ begin perform remove_member((select v::uuid from t where k='grp'),'11111111-1111-1111-1111-111111111111'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'owner removes self → %', sqlerrm; end $$;
\echo '--- 4. Owner deletes the group (floor + members go with it)'
select delete_channel((select v::uuid from t where k='grp')) as deleted;
select (select count(*) from channels where id=(select v::uuid from t where k='grp')) as channel_rows,
       (select count(*) from channel_members where channel_id=(select v::uuid from t where k='grp')) as member_rows,
       (select count(*) from floor_state where channel_id=(select v::uuid from t where k='grp')) as floor_rows;
\echo '--- 5. Admin (Cara, not a member) can delete any channel'
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select delete_channel((select v::uuid from t where k='pub'))->>'deleted' as admin_deleted;
select count(*) as log_rows_for_deletes from connection_log where event='channel_deleted';
\echo '--- 6. Private call: either person can delete it'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
insert into t select 'dm', open_direct_call('44444444-4444-4444-4444-444444444444');
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',false) \g /dev/null
select delete_channel((select v::uuid from t where k='dm'))->>'deleted' as other_person_deleted_call;
\echo '=== ALL CHANNEL MANAGEMENT CHECKS RAN ==='
