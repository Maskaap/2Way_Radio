/**
 * POST /api/token   { channelId, deviceUid }
 * Header: Authorization: Bearer <Supabase access token>
 *
 * Returns a short-lived LiveKit token for the channel's audio room.
 * The LiveKit room name is the channel UUID; participant identity is the user UUID.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { AccessToken, TrackSource } from "livekit-server-sdk";
import { env, supabaseAdmin } from "../lib/env";
import { bearer, handler, HttpError } from "../lib/http";
import { decideToken } from "../lib/tokenPolicy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  const jwt = bearer(req);
  const body = (typeof req.body === "string" ? JSON.parse(req.body) : req.body) ?? {};
  const channelId = String(body.channelId ?? "");
  const deviceUid = String(body.deviceUid ?? "");
  if (!UUID.test(channelId)) throw new HttpError(400, "invalid_channel_id");
  if (deviceUid.length < 8 || deviceUid.length > 128) throw new HttpError(400, "invalid_device_uid");

  const db = supabaseAdmin();
  const { data: userData, error: userErr } = await db.auth.getUser(jwt);
  if (userErr || !userData.user) throw new HttpError(401, "invalid_token");
  const userId = userData.user.id;

  const [profile, channel, membership, device] = await Promise.all([
    db.from("profiles").select("id, display_name, disabled").eq("id", userId).maybeSingle(),
    db.from("channels").select("id, kind, archived").eq("id", channelId).maybeSingle(),
    db.from("channel_members").select("can_talk").eq("channel_id", channelId).eq("user_id", userId).maybeSingle(),
    db.from("devices").select("blocked, blocked_reason, user_id").eq("device_uid", deviceUid).maybeSingle(),
  ]);
  for (const r of [profile, channel, membership, device]) if (r.error) throw r.error;

  const decision = decideToken({
    profile: profile.data, channel: channel.data, membership: membership.data, device: device.data,
  });
  if (!decision.ok) throw new HttpError(decision.status, decision.code, decision.message);

  const at = new AccessToken(env.livekitKey, env.livekitSecret, {
    identity: userId,
    name: profile.data!.display_name,
    ttl: env.tokenTtl,
    metadata: JSON.stringify({ deviceUid }),
  });
  at.addGrant({
    room: channelId,
    roomJoin: true,
    canSubscribe: true,
    canPublish: decision.canPublish,
    canPublishSources: [TrackSource.MICROPHONE],
    canPublishData: true, // used for "who is talking" hints; authority is the DB floor
  });

  res.status(200).json({ url: env.livekitUrl, token: await at.toJwt(), canPublish: decision.canPublish });
});
