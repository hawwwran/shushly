// Privacy-minimal structured logging. One line per request.
// NEVER log notification title/body, user_instruction, bearer tokens, or the OpenAI key.

export interface LogEntry {
  ts: string;
  req_id: string;
  route: string;
  status: number;
  decision: string | null;
  latency_ms: number | null;
  model: string | null;
  token_fp: string | null;
}

export function logRequest(entry: LogEntry): void {
  console.log(JSON.stringify(entry));
}

export function logEvent(event: string, fields: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ ts: new Date().toISOString(), event, ...fields }));
}
