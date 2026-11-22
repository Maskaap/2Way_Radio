/**
 * POST /api/pay/notify   – PayFast ITN (Instant Transaction Notification). Not called by users.
 * Every notification is: signature-checked → confirmed with PayFast's server → applied atomically
 * in the database (amount and duplicates are checked there).
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabaseAdmin } from "../../lib/env";
import { handler, HttpError, rawBody } from "../../lib/http";
import { itnParamString, itnServerConfirm, itnSignatureValid, payfastConfig, randToCents } from "../../lib/payfast";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Posted fields in the order PayFast sent them (order matters for the signature). */
async function orderedFields(req: VercelRequest): Promise<[string, string][]> {
  const b: unknown = (req as any).body;
  if (b && typeof b === "object" && !Buffer.isBuffer(b)) {
    return Object.entries(b as Record<string, unknown>).map(([k, v]) => [k, Array.isArray(v) ? String(v[0]) : String(v ?? "")]);
  }
  return [...new URLSearchParams(await rawBody(req)).entries()];
}

export default handler(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== "POST") throw new HttpError(405, "method_not_allowed");
  const cfg = payfastConfig();
  if (!cfg) throw new HttpError(503, "payments_not_configured");

  const pairs = await orderedFields(req);
  const f = Object.fromEntries(pairs);

  if (!itnSignatureValid(pairs, cfg.passphrase)) {
    console.warn("ITN rejected: bad signature", f.m_payment_id);
    throw new HttpError(400, "bad_signature");
  }
  if (f.merchant_id !== cfg.merchantId) throw new HttpError(400, "wrong_merchant");
  if (!(await itnServerConfirm(cfg, itnParamString(pairs)))) {
    console.warn("ITN rejected: PayFast did not confirm", f.m_payment_id);
    throw new HttpError(400, "not_confirmed");
  }

  const { data, error } = await supabaseAdmin().rpc("billing_apply_itn", {
    p_payment_id: UUID.test(f.m_payment_id ?? "") ? f.m_payment_id : null,
    p_pf_payment_id: f.pf_payment_id ?? null,
    p_status: (f.payment_status ?? "").toUpperCase(),
    p_amount_cents: randToCents(f.amount_gross ?? "0"),
    p_token: f.token || null,
    p_raw: f,
  });
  if (error) throw error;
  console.info("ITN applied", f.m_payment_id, f.payment_status, data);
  res.status(200).send("OK");
});
