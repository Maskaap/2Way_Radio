/**
 * POST /api/livekit-webhook   (configure this URL in LiveKit Cloud -> Settings -> Webhooks)
 *
 * Server-side half-duplex enforcement: when an audio track is published in a channel
 * room by someone who does NOT hold the floor in Supabase, the track is muted by the server.
 * The well-behaved app never does this; this protects against modified or buggy clients.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { RoomServiceClient, TrackType, WebhookReceiver } from "livekit-server-sdk";
import { env, supabaseAdmin } from "../lib/env";
import { handler, HttpError, rawBody } from "../lib/http";

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  const body = await rawBody(req);
  const receiver = new WebhookReceiver(env.livekitKey, env.livekitSecret);
  let event;
  try {
    event = await receiver.receive(body, req.headers.authorization as string | undefined);
  } catch {
    throw new HttpError(401, "invalid_signature");
  }

  if (event.event === "track_published" && event.track?.type === TrackType.AUDIO) {
    const room = event.room?.name;
    const identity = event.participant?.identity;
    const trackSid = event.track.sid;
    if (room && identity && trackSid) {
      const { data: holder, error } = await supabaseAdmin().rpc("floor_holder", { p_channel_id: room });
      if (error) throw error;
      if (holder !== identity) {
        const rooms = new RoomServiceClient(env.livekitApiUrl, env.livekitKey, env.livekitSecret);
        try {
          await rooms.mutePublishedTrack(room, identity, trackSid, true);
          console.warn(`muted non-floor publisher ${identity} in ${room}`);
        } catch (e) {
          // Track may already be gone (very short transmission). Not an error.
          console.info("mute skipped", (e as Error).message);
        }
      }
    }
  }

  if (event.event === "participant_left" && event.room?.name && event.participant?.identity) {
    // Free the floor immediately if the holder dropped out of the room.
    await supabaseAdmin()
      .from("floor_state")
      .update({ holder_id: null, holder_name: null, granted_at: null, lease_until: null, max_until: null, updated_at: new Date().toISOString() })
      .eq("channel_id", event.room.name)
      .eq("holder_id", event.participant.identity);
  }

  res.status(200).json({ ok: true });
});
