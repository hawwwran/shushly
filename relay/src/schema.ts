import { z } from "zod";

// ---- Shared enums (single source of truth for prompt schema + output validation) ----

export const DECISIONS = ["alert", "silent"] as const;

export const REASON_CODES = [
  "ALERT_TIME_SENSITIVE_ACTION",
  "ALERT_SAFETY_OR_EMERGENCY",
  "ALERT_WORK_INCIDENT",
  "SILENT_ROUTINE",
  "SILENT_MARKETING",
  "SILENT_GROUP_SUMMARY",
  "SILENT_LOW_CONFIDENCE",
] as const;

// ---- Request validation (shape only; sanitization/truncation happens in normalizeRequest) ----

// Accept missing-or-null for free-text fields and normalize to null.
const optionalText = z
  .string()
  .nullish()
  .transform((v) => v ?? null);

export const RequestSchema = z.object({
  schema_version: z.literal(1),
  event_id: z.string().min(1),
  app: z.object({
    package_name: z.string().min(1),
    label: z.string().min(1),
  }),
  notification: z.object({
    title: optionalText,
    body: optionalText,
    category: optionalText,
    posted_at: z.string().optional(),
  }),
  policy: z
    .object({
      locale: z.string().optional(),
      default_on_ambiguity: z.string().optional(),
      user_instruction: optionalText,
    })
    .optional()
    .default({}),
});

export type ParsedRequest = z.infer<typeof RequestSchema>;

// ---- Sanitization + truncation (spec section 7.3) ----

// Strip control characters and zero-width / BOM code points. Implemented by code
// point (not a regex literal) so the source stays pure ASCII. Kept: TAB (0x09),
// LF (0x0A), CR (0x0D). Stripped: other C0 controls, DEL + C1 controls
// (0x7F-0x9F), and the zero-width / word-joiner / BOM set.
function isStrippable(cp: number): boolean {
  if (cp <= 0x08) return true;
  if (cp === 0x0b || cp === 0x0c) return true;
  if (cp >= 0x0e && cp <= 0x1f) return true;
  if (cp >= 0x7f && cp <= 0x9f) return true;
  if (cp === 0x200b || cp === 0x200c || cp === 0x200d) return true;
  if (cp === 0x2060 || cp === 0xfeff) return true;
  return false;
}

function sanitize(s: string): string {
  let out = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0);
    if (cp !== undefined && isStrippable(cp)) continue;
    out += ch;
  }
  return out;
}

function clean(s: string | null): string | null {
  return s === null ? null : sanitize(s);
}

function truncate(s: string | null, max: number): string | null {
  if (s === null) return null;
  return s.length > max ? s.slice(0, max) : s;
}

export interface CleanRequest {
  event_id: string;
  app: { package_name: string; label: string };
  notification: {
    title: string | null;
    body: string | null;
    category: string | null;
    posted_at: string | null;
  };
  policy: {
    locale: string;
    default_on_ambiguity: string;
    user_instruction: string | null;
  };
}

export function normalizeRequest(data: ParsedRequest): CleanRequest {
  const title = truncate(clean(data.notification.title), 256);
  let body = truncate(clean(data.notification.body), 1500);
  const category = truncate(clean(data.notification.category), 256);

  // Combined title+body cap (defensive; individual caps already keep it under 2000).
  if (title !== null && body !== null && title.length + body.length > 2000) {
    body = body.slice(0, Math.max(0, 2000 - title.length));
  }

  const userInstruction = truncate(clean(data.policy.user_instruction), 500);

  return {
    event_id: data.event_id,
    app: { package_name: data.app.package_name, label: data.app.label },
    notification: {
      title,
      body,
      category,
      posted_at: data.notification.posted_at ?? null,
    },
    policy: {
      locale: data.policy.locale ?? "en",
      default_on_ambiguity: data.policy.default_on_ambiguity ?? "silent",
      user_instruction: userInstruction,
    },
  };
}

// First validation issue rendered without echoing any field values.
export function firstIssue(error: z.ZodError): string {
  const issue = error.issues[0];
  if (!issue) return "invalid request";
  const path = issue.path.join(".") || "(root)";
  return `${path}: ${issue.code}`;
}

// ---- Model output validation (don't trust the model) ----

function clamp01(n: number): number {
  const x = Number(n);
  if (!Number.isFinite(x)) return 0;
  return Math.max(0, Math.min(1, x));
}

const REASON_CODE_SET: ReadonlySet<string> = new Set(REASON_CODES);

// reason_code must stay consistent with decision: an "alert" never carries a SILENT_*
// reason and a "silent" never carries an ALERT_* reason. Anything out-of-enum or
// cross-category falls back to the decision's default. Applied at the object level
// because a field-level .catch cannot see the sibling decision.
function normalizeReasonCode(
  code: string,
  decision: (typeof DECISIONS)[number],
): (typeof REASON_CODES)[number] {
  const prefix = decision === "alert" ? "ALERT_" : "SILENT_";
  if (REASON_CODE_SET.has(code) && code.startsWith(prefix)) {
    return code as (typeof REASON_CODES)[number];
  }
  return decision === "alert" ? "ALERT_TIME_SENSITIVE_ACTION" : "SILENT_LOW_CONFIDENCE";
}

// decision is strict (an invalid value fails the whole parse -> upstream error).
// confidence / user_visible_reason stay tolerant: clamp / truncate per spec.
export const ModelOutputSchema = z
  .object({
    decision: z.enum(DECISIONS),
    confidence: z.coerce.number().catch(0).transform(clamp01),
    reason_code: z.string().catch(""),
    user_visible_reason: z
      .string()
      .catch("")
      .transform((s) => s.slice(0, 160)),
  })
  .transform((o) => ({
    decision: o.decision,
    confidence: o.confidence,
    reason_code: normalizeReasonCode(o.reason_code, o.decision),
    user_visible_reason: o.user_visible_reason,
  }));

export type ModelOutput = z.infer<typeof ModelOutputSchema>;
