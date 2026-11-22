/**
 * PayFast (South Africa) – checkout signature, ITN validation and subscription API.
 * Behaviour mirrors PayFast's official PHP SDK (github.com/Payfast/payfast-php-sdk):
 *  - checkout signature: fields in PayFast's documented order, empty values skipped,
 *    PHP-style urlencode of trimmed values, passphrase appended last, MD5.
 *  - ITN: all posted fields up to "signature" in received order + passphrase, MD5;
 *    then server-to-server confirmation at /eng/query/validate must answer "VALID".
 *  - API: all headers/body fields + passphrase sorted alphabetically, MD5.
 */
import crypto from "node:crypto";

export interface PayfastConfig {
  merchantId: string;
  merchantKey: string;
  passphrase: string;
  sandbox: boolean;
}

export function payfastConfig(): PayfastConfig | null {
  const merchantId = process.env.PAYFAST_MERCHANT_ID?.trim();
  const merchantKey = process.env.PAYFAST_MERCHANT_KEY?.trim();
  const passphrase = process.env.PAYFAST_PASSPHRASE?.trim() ?? "";
  if (!merchantId || !merchantKey) return null;
  return { merchantId, merchantKey, passphrase, sandbox: (process.env.PAYFAST_SANDBOX ?? "true").trim() !== "false" };
}

export const processUrl = (c: PayfastConfig) =>
  `${c.sandbox ? "https://sandbox.payfast.co.za" : "https://www.payfast.co.za"}/eng/process`;

/** Exactly PHP's urlencode(): RFC1738 – spaces become "+", and !'()*~ are percent-encoded. */
export function phpUrlencode(v: string): string {
  return encodeURIComponent(v)
    .replace(/[!'()*~]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%20/g, "+");
}

const md5 = (s: string) => crypto.createHash("md5").update(s, "utf8").digest("hex");

/** PayFast's documented checkout attribute order. */
export const CHECKOUT_FIELDS = [
  "merchant_id", "merchant_key", "return_url", "cancel_url", "notify_url", "notify_method",
  "name_first", "name_last", "email_address", "cell_number", "m_payment_id", "amount", "item_name",
  "item_description", "custom_int1", "custom_int2", "custom_int3", "custom_int4", "custom_int5",
  "custom_str1", "custom_str2", "custom_str3", "custom_str4", "custom_str5", "email_confirmation",
  "confirmation_address", "currency", "payment_method", "subscription_type", "billing_date",
  "recurring_amount", "frequency", "cycles", "subscription_notify_email", "subscription_notify_webhook",
  "subscription_notify_buyer",
] as const;

/** Returns the fields in PayFast order (empty values dropped) plus their signature. */
export function signCheckout(data: Record<string, string | undefined>, passphrase: string): [string, string][] {
  const fields: [string, string][] = [];
  for (const k of CHECKOUT_FIELDS) {
    const v = data[k];
    if (v !== undefined && v !== null && String(v).trim() !== "") fields.push([k, String(v).trim()]);
  }
  let s = fields.map(([k, v]) => `${k}=${phpUrlencode(v)}`).join("&");
  if (passphrase) s += `&passphrase=${phpUrlencode(passphrase.trim())}`;
  return [...fields, ["signature", md5(s)]];
}

/** Param string of an ITN: every field before "signature", in the order received. */
export function itnParamString(pairs: [string, string][]): string {
  const out: string[] = [];
  for (const [k, v] of pairs) {
    if (k === "signature") break;
    out.push(`${k}=${phpUrlencode(v)}`);
  }
  return out.join("&");
}

export function itnSignatureValid(pairs: [string, string][], passphrase: string): boolean {
  const sig = pairs.find(([k]) => k === "signature")?.[1];
  if (!sig) return false;
  let s = itnParamString(pairs);
  if (passphrase) s += `&passphrase=${phpUrlencode(passphrase)}`;
  return md5(s) === sig;
}

/** Server-to-server confirmation; PayFast answers "VALID". */
export async function itnServerConfirm(c: PayfastConfig, paramString: string): Promise<boolean> {
  const host = c.sandbox ? "https://sandbox.payfast.co.za" : "https://www.payfast.co.za";
  const r = await fetch(`${host}/eng/query/validate`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: paramString,
  });
  return (await r.text()).trim() === "VALID";
}

/** API signature: all header/body values + passphrase, keys sorted alphabetically. */
export function apiSignature(data: Record<string, string>, passphrase: string): string {
  const all: Record<string, string> = { ...data };
  if (passphrase) all.passphrase = passphrase;
  return md5(Object.keys(all).sort().filter((k) => k !== "signature")
    .map((k) => `${k}=${phpUrlencode(all[k])}`).join("&"));
}

/** PHP date("Y-m-d\TH:i:sO") in UTC, e.g. 2026-11-22T13:05:00+0000 */
export function apiTimestamp(d = new Date()): string {
  return d.toISOString().replace(/\.\d{3}Z$/, "+0000");
}

export async function cancelSubscription(c: PayfastConfig, token: string): Promise<{ ok: boolean; detail: string }> {
  const headers: Record<string, string> = { "merchant-id": c.merchantId, version: "v1", timestamp: apiTimestamp() };
  headers.signature = apiSignature(headers, c.passphrase);
  const url = `https://api.payfast.co.za/subscriptions/${encodeURIComponent(token)}/cancel${c.sandbox ? "?testing=true" : ""}`;
  const r = await fetch(url, { method: "PUT", headers });
  const text = await r.text();
  return { ok: r.ok && /success/i.test(text), detail: text.slice(0, 300) };
}

export const randToCents = (s: string) => Math.round(Number.parseFloat(s) * 100);
export const centsToRand = (c: number) => (c / 100).toFixed(2);
