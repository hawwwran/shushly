import { createHash, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";

// sha256(token) truncated to 8 hex chars. Used for logging and rate-limit keying.
// One-way: safe to log; never exposes the token itself.
export function tokenFingerprint(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex").slice(0, 8);
}

// Read the valid device tokens lazily so the .env file (loaded at server start,
// after module imports) is already in process.env by the time the first request lands.
let validTokens: string[] | null = null;
function getValidTokens(): string[] {
  if (validTokens === null) {
    validTokens = (process.env.DEVICE_TOKENS ?? "")
      .split(",")
      .map((t) => t.trim())
      .filter((t) => t.length > 0);
  }
  return validTokens;
}

// Constant-time equality. Length guard first (timingSafeEqual requires equal lengths).
function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a, "utf8");
  const bb = Buffer.from(b, "utf8");
  if (ab.length !== bb.length) return false;
  return timingSafeEqual(ab, bb);
}

function isValidToken(token: string): boolean {
  // OR every comparison without short-circuiting, so an early match does not
  // skip later constant-time checks.
  let ok = false;
  for (const candidate of getValidTokens()) {
    ok = safeEqual(token, candidate) || ok;
  }
  return ok;
}

function parseBearer(req: IncomingMessage): string | null {
  const header = req.headers["authorization"];
  if (typeof header !== "string") return null;
  const trimmed = header.trim();
  if (!trimmed.toLowerCase().startsWith("bearer ")) return null;
  const token = trimmed.slice("bearer ".length).trim();
  return token.length > 0 ? token : null;
}

export interface AuthResult {
  ok: boolean;
  // Fingerprint of whatever token was presented (valid or not), for logging.
  // null when no bearer token was supplied at all.
  fingerprint: string | null;
}

export function checkAuth(req: IncomingMessage): AuthResult {
  const token = parseBearer(req);
  if (token === null) return { ok: false, fingerprint: null };
  return { ok: isValidToken(token), fingerprint: tokenFingerprint(token) };
}
