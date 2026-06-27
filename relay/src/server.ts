import http, { type IncomingMessage, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";

// Load .env (if present) before reading any configuration. Node's built-in
// env-file support — no dotenv dependency. Real environment vars take precedence.
try {
  process.loadEnvFile();
} catch {
  // No .env file: rely on the ambient environment.
}

import { checkAuth } from "./auth.js";
import { consume } from "./limits.js";
import { classify, UpstreamError } from "./classify.js";
import { RequestSchema, normalizeRequest, firstIssue } from "./schema.js";
import { logRequest, logEvent } from "./log.js";

const PORT = Number(process.env.PORT) || 8787;
const MODEL = process.env.OPENAI_MODEL || "gpt-4.1-mini";
const KILL_SWITCH = process.env.KILL_SWITCH === "1";
const MAX_BODY_BYTES = 8 * 1024;

class PayloadTooLargeError extends Error {}

// Read the request body, rejecting once it crosses the byte cap. On overflow we keep
// the socket draining (rather than destroying it) so the 413 response can flush
// cleanly to the client; only a genuinely abusive over-send is hard-aborted.
const ABUSE_LIMIT = 1024 * 1024;
function readBody(req: IncomingMessage, limit: number): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0;
    let settled = false;
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (settled) {
        if (size > ABUSE_LIMIT) req.destroy();
        return;
      }
      if (size > limit) {
        settled = true;
        reject(new PayloadTooLargeError());
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks).toString("utf8"));
    });
    req.on("error", (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    });
  });
}

const KILL_SWITCH_BODY = (eventId: string) => ({
  schema_version: 1,
  event_id: eventId,
  decision: "silent",
  confidence: 0,
  reason_code: "SILENT_LOW_CONFIDENCE",
  user_visible_reason: "Classification paused.",
  model: "killswitch",
  latency_ms: 0,
});

interface LogExtra {
  decision?: string | null;
  model?: string | null;
  token_fp?: string | null;
}

async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const reqId = randomUUID();
  const startedAt = Date.now();
  const method = req.method ?? "";
  const route = (req.url ?? "").split("?")[0] ?? "";

  let responded = false;
  const done = (status: number, payload: unknown, extra: LogExtra = {}): void => {
    if (responded) return;
    responded = true;
    if (!res.writableEnded) {
      res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify(payload));
    }
    logRequest({
      ts: new Date().toISOString(),
      req_id: reqId,
      route: `${method} ${route}`,
      status,
      decision: extra.decision ?? null,
      latency_ms: Date.now() - startedAt,
      model: extra.model ?? null,
      token_fp: extra.token_fp ?? null,
    });
  };

  // 1. Liveness — no auth.
  if (method === "GET" && route === "/health") {
    done(200, { ok: true });
    return;
  }

  // 2. Token check — bearer auth, no OpenAI call.
  if (method === "GET" && route === "/v1/auth-check") {
    const auth = checkAuth(req);
    if (!auth.ok) {
      done(401, { error: "unauthorized" }, { token_fp: auth.fingerprint });
      return;
    }
    done(200, { ok: true, model: MODEL }, { token_fp: auth.fingerprint });
    return;
  }

  // 3. Classification — bearer auth + the full pipeline.
  if (method === "POST" && route === "/v1/classify-notification") {
    const auth = checkAuth(req);
    if (!auth.ok) {
      done(401, { error: "unauthorized" }, { token_fp: auth.fingerprint });
      return;
    }
    const fp = auth.fingerprint;

    let raw: string;
    try {
      raw = await readBody(req, MAX_BODY_BYTES);
    } catch (err) {
      if (err instanceof PayloadTooLargeError) {
        done(413, { error: "payload_too_large" }, { token_fp: fp });
      } else {
        done(400, { error: "bad_request", detail: "unreadable body" }, { token_fp: fp });
      }
      return;
    }

    let json: unknown;
    try {
      json = JSON.parse(raw);
    } catch {
      done(400, { error: "bad_request", detail: "invalid JSON" }, { token_fp: fp });
      return;
    }

    const parsed = RequestSchema.safeParse(json);
    if (!parsed.success) {
      done(400, { error: "bad_request", detail: firstIssue(parsed.error) }, { token_fp: fp });
      return;
    }
    const request = normalizeRequest(parsed.data);

    // Kill switch: skip OpenAI entirely, fail safe to silent. Does not consume budget.
    if (KILL_SWITCH) {
      done(200, KILL_SWITCH_BODY(request.event_id), {
        decision: "silent",
        model: "killswitch",
        token_fp: fp,
      });
      return;
    }

    const limit = consume(fp as string);
    if (!limit.allowed) {
      done(429, { error: "rate_limited" }, { token_fp: fp });
      return;
    }

    try {
      const result = await classify(request, MODEL);
      done(
        200,
        {
          schema_version: 1,
          event_id: request.event_id,
          decision: result.decision,
          confidence: result.confidence,
          reason_code: result.reason_code,
          user_visible_reason: result.user_visible_reason,
          model: result.model,
          latency_ms: result.latency_ms,
        },
        { decision: result.decision, model: result.model, token_fp: fp },
      );
    } catch (err) {
      const reason = err instanceof UpstreamError ? err.message : "internal";
      done(502, { error: "upstream_error", detail: reason }, { token_fp: fp });
    }
    return;
  }

  // 4. Anything else.
  done(405, { error: "method_not_allowed" });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch(() => {
    if (!res.headersSent) {
      res.writeHead(500, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ error: "internal_error" }));
    }
    logEvent("handler_error", { route: `${req.method ?? ""} ${(req.url ?? "").split("?")[0] ?? ""}` });
  });
});

server.listen(PORT, () => {
  logEvent("ready", {
    port: PORT,
    model: MODEL,
    kill_switch: KILL_SWITCH,
    // Presence/counts only — never the secret values themselves.
    openai_key_present: Boolean(process.env.OPENAI_API_KEY),
    device_tokens_configured: (process.env.DEVICE_TOKENS ?? "")
      .split(",")
      .map((t) => t.trim())
      .filter((t) => t.length > 0).length,
  });
});

server.on("error", (err) => {
  logEvent("server_error", { message: err.message });
  process.exitCode = 1;
});
