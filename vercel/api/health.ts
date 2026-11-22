import type { VercelRequest, VercelResponse } from "@vercel/node";
import { supabaseAdmin } from "../lib/env";
import { handler } from "../lib/http";

/** GET /api/health – checks env vars and the Supabase connection. */
export default handler(async (_req: VercelRequest, res: VercelResponse) => {
  const checks: Record<string, string> = {};
  for (const k of ["SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY", "LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"]) {
    checks[k] = process.env[k] ? "set" : "MISSING";
  }
  try {
    const { error } = await supabaseAdmin().from("channels").select("id", { head: true, count: "exact" });
    checks.database = error ? `error: ${error.message}` : "ok";
  } catch (e) {
    checks.database = `error: ${(e as Error).message}`;
  }
  const ok = Object.values(checks).every((v) => v === "set" || v === "ok");
  res.status(ok ? 200 : 500).json({ ok, checks });
});
