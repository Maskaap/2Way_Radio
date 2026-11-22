\set ON_ERROR_STOP 1
\pset footer off
insert into auth.users(id,email,raw_user_meta_data) values
 ('22222222-2222-2222-2222-222222222222','ben@x.co','{"display_name":"Ben"}'),
 ('33333333-3333-3333-3333-333333333333','cara@x.co','{"display_name":"Cara"}');
update public.profiles set role='admin' where id='33333333-3333-3333-3333-333333333333';
set role authenticated;

\echo '1. Guard scans an unregistered tag → logged as unknown'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select record_tag_scan('04:a2:3b:c4:d5:80', -26.20, 28.04)->>'known' as known_before_registration;
\echo '2. Guard cannot register tags'
do $$ begin perform register_tag('04A23BC4D580','Hack'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'guard registers tag → %', sqlerrm; end $$;
\echo '3. Admin registers it as "Gate 3" (every 60 min); earlier unknown scan is linked'
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select register_tag('04a23bc4d580', 'Gate 3', 'Main depot', -26.2001, 28.0401, 60)->>'tag_uid' as stored_uid;
select count(*) filter (where known) as linked_scans from tag_scans;
\echo '4. Guard scans again: known; a double-read within 30 s counts once'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
reset role; delete from tag_scans; set role authenticated;
select record_tag_scan('04A23BC4D580', -26.2001, 28.0401)->>'tag_name' as scanned;
select record_tag_scan('04A23BC4D580', -26.2001, 28.0401)->>'duplicate' as second_read_duplicate;
select count(*) as scans_stored from tag_scans;
\echo '5. Guard sees only own scans; cannot see the tag list'
select count(*) as guard_sees_tags from nfc_tags;
\echo '6. 70 minutes pass with no scan → one "missed" alert (not repeated)'
reset role; update tag_scans set scanned_at = now() - interval '70 minutes'; set role authenticated;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select check_missed_checkpoints() as alerts_raised, check_missed_checkpoints() as raised_again;
select message from alerts where kind='checkpoint_missed';
select name, overdue, last_by from checkpoint_status();
\echo '7. Guard scans → missed alert auto-resolved, checkpoint OK'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
select record_tag_scan('04A23BC4D580', null, null)->>'known' as known;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false) \g /dev/null
select status from alerts where kind='checkpoint_missed';
select name, overdue from checkpoint_status();
\echo '8. Guard cannot run the missed-checkpoint check'
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false) \g /dev/null
do $$ begin perform check_missed_checkpoints(); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'guard runs check → %', sqlerrm; end $$;
\echo '9. Garbage tag id is rejected'
do $$ begin perform record_tag_scan('xyz'); raise exception 'SHOULD_FAIL'; exception when others then
  if sqlerrm='SHOULD_FAIL' then raise; end if; raise notice 'bad tag → %', sqlerrm; end $$;
\echo '=== ALL NFC CHECKS RAN ==='
