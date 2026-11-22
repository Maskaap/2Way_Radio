import type { VercelRequest, VercelResponse } from "@vercel/node";

export class HttpError extends Error {
  constructor(public status: number, public code: string, message?: string) {
    super(message ?? code);
  }
}

export function handler(fn: (req: VercelRequest, res: VercelResponse) => Promise<void>) {
  return async (req: VercelRequest, res: VercelResponse) => {
    try {
      await fn(req, res);
    } catch (e) {
      if (e instanceof HttpError) {
        res.status(e.status).json({ error: e.code, message: e.message });
      } else {
        console.error(e);
        res.status(500).json({ error: "internal_error" });
      }
    }
  };
}

export function bearer(req: VercelRequest): string {
  const h = req.headers.authorization ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(h);
  if (!m) throw new HttpError(401, "missing_token");
  return m[1];
}

/** Returns the exact request body as text (needed for webhook signature checks). */
export async function rawBody(req: VercelRequest): Promise<string> {
  const b: unknown = (req as any).body;
  if (Buffer.isBuffer(b)) return b.toString("utf8");
  if (typeof b === "string") return b;
  if (b && typeof b === "object") return JSON.stringify(b);
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(typeof c === "string" ? Buffer.from(c) : c);
  return Buffer.concat(chunks).toString("utf8");
}
