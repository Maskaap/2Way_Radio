/**
 * Pure decision logic for issuing a LiveKit token, kept separate from I/O so it can be unit-tested.
 */
export interface TokenContext {
  profile: { id: string; display_name: string; disabled: boolean } | null;
  channel: { id: string; kind: string; archived: boolean } | null;
  membership: { can_talk: boolean } | null;
  device: { blocked: boolean; blocked_reason: string | null; user_id: string | null } | null;
  /** Result of access_for(): false when a subscription is required and missing/expired. */
  hasAccess: boolean;
}

export type TokenDecision =
  | { ok: true; canPublish: boolean }
  | { ok: false; status: number; code: string; message?: string };

export function decideToken(ctx: TokenContext): TokenDecision {
  if (!ctx.profile) return { ok: false, status: 403, code: "no_profile" };
  if (ctx.profile.disabled) return { ok: false, status: 403, code: "account_disabled" };
  if (!ctx.device) return { ok: false, status: 403, code: "device_not_registered" };
  if (ctx.device.blocked) {
    return { ok: false, status: 403, code: "device_blocked", message: ctx.device.blocked_reason ?? undefined };
  }
  if (ctx.device.user_id !== ctx.profile.id) return { ok: false, status: 403, code: "device_not_registered" };
  if (!ctx.hasAccess) return { ok: false, status: 402, code: "subscription_required" };
  if (!ctx.channel || ctx.channel.archived) return { ok: false, status: 404, code: "channel_not_found" };
  // Membership is created by enter_channel() for public channels, so it's required for all kinds.
  if (!ctx.membership) return { ok: false, status: 403, code: "not_a_member" };
  return { ok: true, canPublish: ctx.membership.can_talk };
}
