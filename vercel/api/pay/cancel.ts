/**
 * POST /api/pay/cancel   (Authorization: Bearer <Supabase access token>)
 * Cancels the signed-in user's PayFast subscription. Access continues until the paid period ends.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabaseAdmin } from "../../lib/env";
import { bearer, handler, HttpError } from "../../lib/http";
import { cancelSubscription, payfastConfig } from "../../lib/payfast";

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  const cfg = payfastConfig();
  if (!cfg) throw new HttpError(503, "payments_not_configured");
  const db = supabaseAdmin();
  const { data: u, error: ue } = await db.auth.getUser(bearer(req));
  if (ue || !u.user) throw new HttpError(401, "invalid_token");

  const { data: sub, error } = await db.from("subscriptions")
    .select("provider, provider_token, status, current_period_end").eq("user_id", u.user.id).maybeSingle();
  if (error) throw error;
  if (!sub) throw new HttpError(404, "no_subscription");
  if (sub.provider !== "payfast" || !sub.provider_token) {
    throw new HttpError(400, "manual_subscription", "This plan is managed by the administrator");
  }
  if (sub.status !== "cancelled") {
    const r = await cancelSubscription(cfg, sub.provider_token);
    if (!r.ok) {
      console.error("PayFast cancel failed", r.detail);
      throw new HttpError(502, "payfast_cancel_failed", "PayFast could not cancel the subscription right now");
    }
    const { error: e2 } = await db.rpc("billing_mark_cancelled", { p_user: u.user.id });
    if (e2) throw e2;
  }
  res.status(200).json({ ok: true, access_until: sub.current_period_end });
});
