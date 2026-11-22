import type { VercelRequest } from "@vercel/node";

/** Public https origin of this deployment, e.g. https://2way-radio.vercel.app (PUBLIC_BASE_URL overrides). */
export function publicOrigin(req: VercelRequest): string {
  const fixed = process.env.PUBLIC_BASE_URL?.trim().replace(/\/+$/, "");
  if (fixed) return fixed;
  const host = String(req.headers["x-forwarded-host"] ?? req.headers.host ?? "").split(",")[0].trim();
  return `https://${host}`;
}

export const esc = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));
