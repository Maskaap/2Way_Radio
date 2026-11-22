import type { VercelRequest, VercelResponse } from "@vercel/node";
import { handler } from "../lib/http";

/**
 * GET /api/health – checks env vars and the Supabase connection,
 * and explains in plain words what is wrong.
 */
export default handler(async (_req: VercelRequest, res: VercelResponse) => {
  const checks: Record<string, string> = {};
  for (const k of ["SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY", "LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"]) {
    checks[k] = process.env[k] ? "set" : "MISSING";
  }
  // Show only NON-secret hints so you can verify values that Vercel hides as "Sensitive".
  const url = (process.env.SUPABASE_URL ?? "").trim();
  if (url) checks.SUPABASE_URL = `set (${url})`;
  checks.SUPABASE_ANON_KEY = describeKey(process.env.SUPABASE_ANON_KEY, "publishable");
  checks.SUPABASE_SERVICE_ROLE_KEY = describeKey(process.env.SUPABASE_SERVICE_ROLE_KEY, "secret");
  const lk = (process.env.LIVEKIT_URL ?? "").trim();
  if (lk) checks.LIVEKIT_URL = /^wss:\/\/.+/.test(lk) ? `set (${lk})` : `WRONG: must start with wss:// (got "${lk.slice(0, 40)}")`;
  checks.database = await checkDatabase();
  const ok = Object.values(checks).every((v) => v === "ok" || v.startsWith("set"));
  // Payments are optional: shown, but they don't make the server "not ok".
  for (const k of ["PAYFAST_MERCHANT_ID", "PAYFAST_MERCHANT_KEY", "PAYFAST_PASSPHRASE"]) {
    checks[k] = process.env[k] ? "set" : "optional – not set (payments disabled)";
  }
  if (process.env.PAYFAST_MERCHANT_ID) {
    checks.PAYFAST_MODE = (process.env.PAYFAST_SANDBOX ?? "true").trim() !== "false" ? "set (SANDBOX – test payments only)" : "set (LIVE – real money)";
  }
  res.status(ok ? 200 : 500).json({ ok, checks });
});

async function checkDatabase(): Promise<string> {
  const url = (process.env.SUPABASE_URL ?? "").trim().replace(/\/+$/, "");
  const key = (process.env.SUPABASE_SERVICE_ROLE_KEY ?? "").trim();
  if (!url || !key) return "error: SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY missing";
  if (!/^https:\/\/[a-z0-9-]+\.supabase\.co$/i.test(url)) {
    return `error: SUPABASE_URL looks wrong ("${url}") – it must look like https://abcdefgh.supabase.co`;
  }
  if (key.startsWith("sb_publishable_")) {
    return "error: SUPABASE_SERVICE_ROLE_KEY contains the PUBLISHABLE key – use the SECRET key (sb_secret_…)";
  }

  let r: Response;
  try {
    const headers: Record<string, string> = { apikey: key };
    if (key.startsWith("eyJ")) headers.Authorization = `Bearer ${key}`; // legacy JWT-style key
    r = await fetch(`${url}/rest/v1/channels?select=id&limit=1`, { headers });
  } catch (e) {
    return `error: cannot reach SUPABASE_URL (${(e as Error).message}) – check the address`;
  }
  if (r.ok) return "ok";

  const body = await r.text();
  let code = "", msg = body.slice(0, 200);
  try { const j = JSON.parse(body); code = j.code ?? ""; msg = j.message ?? j.msg ?? msg; } catch { /* not JSON */ }

  if (r.status === 401 || r.status === 403) {
    return `error: Supabase rejected the key (${r.status}) – paste the SECRET key (sb_secret_…) into SUPABASE_SERVICE_ROLE_KEY again, then Redeploy`;
  }
  if (code === "PGRST205" || code === "42P01" || r.status === 404) {
    return "error: tables not found – run supabase/migrations/…_init.sql in the Supabase SQL Editor";
  }
  if (r.status === 540 || r.status === 503) {
    return "error: Supabase project is paused or starting – open it in the Supabase dashboard and Restore/Resume";
  }
  return `error: HTTP ${r.status} ${code} ${msg}`.trim();
}

/** Describes a key without revealing it: type + length only. */
function describeKey(v: string | undefined, want: "publishable" | "secret"): string {
  const k = (v ?? "").trim();
  if (!k) return "MISSING";
  const type = k.startsWith("sb_secret_") ? "secret" : k.startsWith("sb_publishable_") ? "publishable"
    : k.startsWith("eyJ") ? "legacy JWT" : "unknown format";
  const hint = `${type} key, ${k.length} characters`;
  if (type === want || type === "legacy JWT") return `set (${hint})`;
  return `WRONG: this is a ${hint} – expected the ${want} key (sb_${want}_…)`;
}
