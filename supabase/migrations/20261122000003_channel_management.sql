-- =====================================================================
-- PTT Connect – channel & group management
-- Run AFTER 20261122000002_billing.sql. Safe to run again.
-- =====================================================================

-- Delete a channel / group / private call permanently.
-- Allowed: the channel OWNER (both people in a private call are owners) or an ADMIN.
-- Members, floor state and invites are removed automatically (ON DELETE CASCADE).
create or replace function public.delete_channel(p_channel_id uuid)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user(); c public.channels; n integer;
begin
  select * into c from public.channels where id = p_channel_id;
  if not found then raise exception 'channel_not_found'; end if;
  if not (public.is_admin() or exists (
      select 1 from public.channel_members where channel_id = p_channel_id and user_id = uid and role = 'owner')) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  select count(*) into n from public.channel_members where channel_id = p_channel_id;
  insert into public.connection_log(event, user_id, channel_id, detail)
  values ('channel_deleted', uid, null, jsonb_build_object('channel_id', c.id, 'name', c.name, 'kind', c.kind, 'members', n));
  update public.profiles set current_channel_id = null where current_channel_id = p_channel_id;
  delete from public.channels where id = p_channel_id;
  return jsonb_build_object('deleted', true, 'name', c.name, 'members', n);
end $$;

-- Remove a member (or yourself = leave). Owners can only be removed by an admin;
-- an owner who wants out should delete the channel instead.
create or replace function public.remove_member(p_channel_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare uid uuid := public.assert_active_user();
begin
  if not exists (select 1 from public.channel_members where channel_id = p_channel_id and user_id = p_user_id) then
    raise exception 'not_a_member';
  end if;
  if p_user_id <> uid and not public.can_manage_channel(p_channel_id) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  if exists (select 1 from public.channel_members where channel_id = p_channel_id and user_id = p_user_id and role = 'owner')
     and not public.is_admin() then
    raise exception 'cannot_remove_owner';
  end if;
  delete from public.channel_members where channel_id = p_channel_id and user_id = p_user_id;
  update public.floor_state set holder_id = null, holder_name = null, granted_at = null, lease_until = null, max_until = null, updated_at = now()
   where channel_id = p_channel_id and holder_id = p_user_id;
  update public.profiles set current_channel_id = null where id = p_user_id and current_channel_id = p_channel_id;
  insert into public.connection_log(event, user_id, channel_id, detail)
  values ('member_removed', p_user_id, p_channel_id, jsonb_build_object('by', uid));
end $$;

revoke execute on function public.delete_channel(uuid), public.remove_member(uuid, uuid) from public, anon;
grant execute on function public.delete_channel(uuid), public.remove_member(uuid, uuid) to authenticated, service_role;
