import { createClient, SupabaseClient } from "@supabase/supabase-js";

function need(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing environment variable ${name}`);
  return v;
}

export const env = {
  get supabaseUrl() { return need("SUPABASE_URL"); },
  get supabaseAnonKey() { return need("SUPABASE_ANON_KEY"); },
  get supabaseServiceKey() { return need("SUPABASE_SERVICE_ROLE_KEY"); },
  get livekitUrl() { return need("LIVEKIT_URL"); },
  get livekitApiUrl() { return need("LIVEKIT_URL").replace(/^ws/, "http"); },
  get livekitKey() { return need("LIVEKIT_API_KEY"); },
  get livekitSecret() { return need("LIVEKIT_API_SECRET"); },
  get tokenTtl() { return process.env.LIVEKIT_TOKEN_TTL || "2h"; },
};

let admin: SupabaseClient | null = null;

/** Service-role client: bypasses RLS. Server-side only. */
export function supabaseAdmin(): SupabaseClient {
  if (!admin) {
    admin = createClient(env.supabaseUrl, env.supabaseServiceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }
  return admin;
}
