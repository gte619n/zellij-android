/** Runtime configuration, resolved from the environment. */
export interface Config {
  port: number;
  stateDir: string;
  /** Budget warn threshold as a fraction of the Opus pool (arch §3). */
  warnFraction: number;
  /** Soft-stop threshold as a fraction of the Opus pool (arch §3). */
  softStopFraction: number;
}

function expandHome(p: string, home: string): string {
  return p.startsWith("~") ? home + p.slice(1) : p;
}

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  const home = env.HOME ?? ".";
  return {
    port: Number(env.ANVIL_PORT ?? 7701),
    stateDir: expandHome(env.ANVIL_STATE_DIR ?? "~/.anvil", home),
    warnFraction: Number(env.ANVIL_BUDGET_WARN ?? 0.8),
    softStopFraction: Number(env.ANVIL_BUDGET_SOFTSTOP ?? 0.95),
  };
}
