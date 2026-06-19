import type { Budget } from "@protocol";

/**
 * Budget tracker — arch §3, decision #9 (load-bearing).
 *
 * STUB for M1: returns a zeroed snapshot against the Max-5x pool. The real tracker
 * (impl plan 1, M8) accumulates Opus/Sonnet usage from ResultEvents, emits `budget`
 * events on change, and drives the warn threshold + soft-stop. Isolated here so the
 * paused Agent-SDK billing split (arch §3) can be re-pointed without touching callers.
 */
export function budgetSnapshot(): Budget {
  const weekMs = 7 * 24 * 60 * 60 * 1000;
  return {
    opus: { usedHrs: 0, limitHrs: 20 },
    sonnet: { usedHrs: 0, limitHrs: 240 },
    windowResetsAt: new Date(Date.now() + weekMs).toISOString(),
    warn: false,
  };
}
