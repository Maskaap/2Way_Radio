/**
 * POST /api/channel   (Authorization: Bearer <Supabase access token>)
 *   { action: "delete", channelId }                    – delete a channel/group/private call (owner or admin)
 *   { action: "remove_member", channelId, userId }     – remove someone (owner/moderator/admin) or leave (yourself)
 *
 * Permissions are enforced by the database functions, which run AS THE CALLING USER.
 * After the database change, people are disconnected from the live audio room immediately.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { createClient } from "@supabase/supabase-js";
import { RoomServiceClient } from "livekit-server-sdk";
import { env } from "../lib/env";
import { bearer, handler, HttpError } from "../lib/http";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  const jwt = bearer(req);
  const body = (typeof req.body === "string" ? JSON.parse(req.body) : req.body) ?? {};
  const action = String(body.action ?? "");
  const channelId = String(body.channelId ?? "");
  const userId = String(body.userId ?? "");
  if (!UUID.test(channelId)) throw new HttpError(400, "invalid_channel_id");

  // Client acting as the signed-in user, so auth.uid() and all permission checks apply.
  const asUser = createClient(env.supabaseUrl, env.supabaseAnonKey, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { Authorization: `Bearer ${jwt}` } },
  });
  const rooms = new RoomServiceClient(env.livekitApiUrl, env.livekitKey, env.livekitSecret);

  if (action === "delete") {
    const { data, error } = await asUser.rpc("delete_channel", { p_channel_id: channelId });
    if (error) throw new HttpError(error.message === "not_allowed" ? 403 : 400, error.message);
    await rooms.deleteRoom(channelId).catch(() => undefined);           // disconnects everyone still in it
    res.status(200).json({ ok: true, ...(data as object) });
    return;
  }

  if (action === "remove_member") {
    if (!UUID.test(userId)) throw new HttpError(400, "invalid_user_id");
    const { error } = await asUser.rpc("remove_member", { p_channel_id: channelId, p_user_id: userId });
    if (error) throw new HttpError(error.message === "not_allowed" ? 403 : 400, error.message);
    await rooms.removeParticipant(channelId, userId).catch(() => undefined); // kick from live audio if connected
    res.status(200).json({ ok: true });
    return;
  }

  throw new HttpError(400, "invalid_action");
});
