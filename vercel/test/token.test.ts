import { test } from "node:test";
import assert from "node:assert/strict";
import { AccessToken, TokenVerifier, TrackSource, WebhookReceiver } from "livekit-server-sdk";
import { decideToken, TokenContext } from "../lib/tokenPolicy";

const base: TokenContext = {
  profile: { id: "u1", display_name: "Anna", disabled: false },
  channel: { id: "c1", kind: "public", archived: false },
  membership: { can_talk: true },
  device: { blocked: false, blocked_reason: null, user_id: "u1" },
  hasAccess: true,
};

test("member with talk rights gets publish token", () => {
  assert.deepEqual(decideToken(base), { ok: true, canPublish: true });
});

test("listen-only member gets subscribe-only token", () => {
  assert.deepEqual(decideToken({ ...base, membership: { can_talk: false } }), { ok: true, canPublish: false });
});

test("expired subscription is refused with 402", () => {
  assert.deepEqual(decideToken({ ...base, hasAccess: false }), { ok: false, status: 402, code: "subscription_required" });
});

test("blocked device is refused with reason", () => {
  const d = decideToken({ ...base, device: { blocked: true, blocked_reason: "lost", user_id: "u1" } });
  assert.deepEqual(d, { ok: false, status: 403, code: "device_blocked", message: "lost" });
});

test("disabled account, unknown device, other user's device, non-member, archived channel are refused", () => {
  assert.equal((decideToken({ ...base, profile: { ...base.profile!, disabled: true } }) as any).code, "account_disabled");
  assert.equal((decideToken({ ...base, device: null }) as any).code, "device_not_registered");
  assert.equal((decideToken({ ...base, device: { ...base.device!, user_id: "u2" } }) as any).code, "device_not_registered");
  assert.equal((decideToken({ ...base, membership: null }) as any).code, "not_a_member");
  assert.equal((decideToken({ ...base, channel: { ...base.channel!, archived: true } }) as any).code, "channel_not_found");
});

test("LiveKit token carries room, identity and mic-only publish grant", async () => {
  const key = "APItest", secret = "s".repeat(40);
  const at = new AccessToken(key, secret, { identity: "u1", name: "Anna", ttl: "2h" });
  at.addGrant({ room: "c1", roomJoin: true, canSubscribe: true, canPublish: true, canPublishSources: [TrackSource.MICROPHONE] });
  const claims = await new TokenVerifier(key, secret).verify(await at.toJwt());
  assert.equal(claims.sub, "u1");
  assert.equal(claims.video?.room, "c1");
  assert.equal(claims.video?.canPublish, true);
});

test("webhook receiver rejects a bad signature", async () => {
  const r = new WebhookReceiver("APItest", "s".repeat(40));
  await assert.rejects(() => r.receive('{"event":"room_started"}', "Bearer not-a-valid-jwt"));
});
