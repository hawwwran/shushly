// In-memory rate / budget guards, keyed by token fingerprint.
// Counters live only in process memory and reset on restart (prototype scope).

function envInt(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw == null || raw.trim() === "") return fallback;
  const v = Number(raw);
  return Number.isInteger(v) && v >= 0 ? v : fallback;
}

interface Caps {
  ratePerMin: number;
  deviceDaily: number;
  globalDaily: number;
}

// Read lazily: .env is loaded at server start, after module imports run.
let caps: Caps | null = null;
function getCaps(): Caps {
  if (caps === null) {
    caps = {
      ratePerMin: envInt("RATE_PER_MIN", 20),
      deviceDaily: envInt("DEVICE_DAILY_CAP", 200),
      globalDaily: envInt("GLOBAL_DAILY_CAP", 500),
    };
  }
  return caps;
}

function utcDate(): string {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD in UTC
}

interface TokenState {
  recentHits: number[]; // epoch-ms timestamps within the last minute
  day: string;
  dayCount: number;
}

const perToken = new Map<string, TokenState>();
let globalDay = utcDate();
let globalCount = 0;

export type LimitReason = "per_minute" | "device_daily" | "global_daily";

export interface LimitResult {
  allowed: boolean;
  reason?: LimitReason;
}

// Check all limits and, only if every one passes, atomically increment the
// counters (no await between check and commit, so single-threaded Node has no
// race). A denied request does not consume budget.
export function consume(fingerprint: string): LimitResult {
  const { ratePerMin, deviceDaily, globalDaily } = getCaps();
  const today = utcDate();
  const now = Date.now();

  if (globalDay !== today) {
    globalDay = today;
    globalCount = 0;
  }

  let state = perToken.get(fingerprint);
  if (state === undefined) {
    state = { recentHits: [], day: today, dayCount: 0 };
    perToken.set(fingerprint, state);
  }
  if (state.day !== today) {
    state.day = today;
    state.dayCount = 0;
  }

  state.recentHits = state.recentHits.filter((t) => now - t < 60_000);

  if (state.recentHits.length >= ratePerMin) return { allowed: false, reason: "per_minute" };
  if (state.dayCount >= deviceDaily) return { allowed: false, reason: "device_daily" };
  if (globalCount >= globalDaily) return { allowed: false, reason: "global_daily" };

  state.recentHits.push(now);
  state.dayCount += 1;
  globalCount += 1;
  return { allowed: true };
}
