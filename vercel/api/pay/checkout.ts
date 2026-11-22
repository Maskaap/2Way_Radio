/**
 * POST /api/pay/checkout   { plan: "basic"|"pro"|…, period: "monthly"|"yearly" }
 * Header: Authorization: Bearer <Supabase access token>
 * → { url }  – open this URL in a browser; it forwards to PayFast's secure payment page.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabaseAdmin } from "../../lib/env";
import { bearer, handler, HttpError } from "../../lib/http";
import { payfastConfig } from "../../lib/payfast";
import { publicOrigin } from "../../lib/origin";

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  if (!payfastConfig()) throw new HttpError(503, "payments_not_configured", "Payments are not set up yet");
  const body = (typeof req.body === "string" ? JSON.parse(req.body) : req.body) ?? {};
  const plan = String(body.plan ?? "").toLowerCase();
  const period = String(body.period ?? "");
  if (!/^[a-z0-9_-]{2,30}$/.test(plan)) throw new HttpError(400, "invalid_plan");
  if (period !== "monthly" && period !== "yearly") throw new HttpError(400, "invalid_period");

  const db = supabaseAdmin();
  const { data: u, error: ue } = await db.auth.getUser(bearer(req));
  if (ue || !u.user) throw new HttpError(401, "invalid_token");

  const { data, error } = await db.rpc("billing_create_checkout", { p_user: u.user.id, p_plan: plan, p_period: period });
  if (error) throw new HttpError(400, error.message);

  res.status(200).json({ url: `${publicOrigin(req)}/api/pay/redirect?id=${data.payment_id}` });
});
