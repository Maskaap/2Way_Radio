/**
 * GET /api/pay/redirect?id=<payment id>
 * Shows a short "Redirecting to PayFast…" page that posts the signed subscription form to PayFast.
 * The payment id is an unguessable UUID created by /api/pay/checkout for a signed-in user.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabaseAdmin } from "../../lib/env";
import { handler, HttpError } from "../../lib/http";
import { centsToRand, payfastConfig, processUrl, signCheckout } from "../../lib/payfast";
import { esc, publicOrigin } from "../../lib/origin";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  const cfg = payfastConfig();
  if (!cfg) throw new HttpError(503, "payments_not_configured");
  const id = String(req.query.id ?? "");
  if (!UUID.test(id)) throw new HttpError(400, "invalid_id");

  const db = supabaseAdmin();
  const { data: pay, error } = await db.from("payments")
    .select("id, user_id, plan_code, period, amount_cents, status, created_at, plans(name)").eq("id", id).maybeSingle();
  if (error) throw error;
  if (!pay || pay.status !== "pending" || Date.now() - Date.parse(pay.created_at) > 2 * 3600_000) {
    throw new HttpError(410, "checkout_expired", "This payment link has expired. Start again from the app.");
  }
  const [{ data: user }, { data: profile }, { data: company }] = await Promise.all([
    db.auth.admin.getUserById(pay.user_id),
    db.from("profiles").select("display_name").eq("id", pay.user_id).maybeSingle(),
    db.from("app_settings").select("value").eq("key", "company_name").maybeSingle(),
  ]);

  const origin = publicOrigin(req);
  const amount = centsToRand(pay.amount_cents);
  const planName = (pay as any).plans?.name ?? pay.plan_code;
  const fields = signCheckout({
    merchant_id: cfg.merchantId,
    merchant_key: cfg.merchantKey,
    return_url: `${origin}/pay/success.html`,
    cancel_url: `${origin}/pay/cancelled.html`,
    notify_url: `${origin}/api/pay/notify`,
    name_first: (profile?.display_name ?? "").slice(0, 100),
    email_address: user?.user?.email ?? undefined,
    m_payment_id: pay.id,
    amount,
    item_name: `${company?.value ?? "PTT Connect"} PTT – ${planName} (${pay.period})`.slice(0, 100),
    custom_str1: pay.user_id,
    custom_str2: pay.plan_code,
    custom_str3: pay.period,
    subscription_type: "1",
    recurring_amount: amount,
    frequency: pay.period === "monthly" ? "3" : "6",   // PayFast: 3 = monthly, 6 = annual
    cycles: "0",                                        // 0 = until cancelled
  }, cfg.passphrase);

  const inputs = fields.map(([k, v]) => `<input type="hidden" name="${esc(k)}" value="${esc(v)}">`).join("\n");
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.status(200).send(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Redirecting to PayFast…</title><link rel="icon" href="/favicon.png">
<style>body{font-family:system-ui,sans-serif;background:#0e1116;color:#e6edf3;display:grid;place-items:center;min-height:100vh;margin:0;text-align:center}
button{margin-top:16px;padding:12px 22px;border-radius:8px;border:0;background:#f47b20;color:#fff;font-size:16px}</style></head>
<body><div><img src="/logo.png" width="110" height="110" alt=""><h2>${esc(planName)} – R${esc(amount)} / ${pay.period === "monthly" ? "month" : "year"}</h2>
<p>Taking you to PayFast's secure payment page…</p>
<form id="pf" action="${processUrl(cfg)}" method="post">${inputs}<button type="submit">Continue to PayFast</button></form></div>
<script>document.getElementById("pf").submit();</script></body></html>`);
});
