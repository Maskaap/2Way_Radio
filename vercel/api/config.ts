/**
 * GET /api/config  – public bootstrap config for the apps and admin page.
 * Only PUBLIC values here (the Supabase anon key is designed to be public; RLS protects data).
 * This means the phone app only needs to know your Vercel URL.
 */
import type { VercelRequest, VercelResponse } from "@vercel/node";
import { env } from "../lib/env";
import { handler } from "../lib/http";

export default handler(async (_req: VercelRequest, res: VercelResponse) => {
  res.status(200).json({
    supabaseUrl: env.supabaseUrl,
    supabaseAnonKey: env.supabaseAnonKey,
    livekitUrl: env.livekitUrl,
    minAppVersion: "0.1.0",
  });
});
