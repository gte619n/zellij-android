/**
 * Auth & billing guard — arch §3 (load-bearing).
 *
 * The daemon MUST drive Claude Code with a subscription OAuth token and MUST NOT have a
 * metered API key in its environment. Claude Code's auth precedence puts ANTHROPIC_API_KEY
 * and ANTHROPIC_AUTH_TOKEN ABOVE CLAUDE_CODE_OAUTH_TOKEN, so a stray key silently switches
 * every turn to metered pay-per-token. This guard is the enforcement point.
 */

export interface AuthStatus {
  subscriptionAuthOk: boolean;
  reason?: string;
}

function isSet(v: string | undefined): boolean {
  return typeof v === "string" && v.trim().length > 0;
}

/** Pure check — no side effects. Used by /api/health and by the startup assertion. */
export function checkAuth(env: Record<string, string | undefined> = process.env): AuthStatus {
  if (!isSet(env.CLAUDE_CODE_OAUTH_TOKEN)) {
    return {
      subscriptionAuthOk: false,
      reason: "CLAUDE_CODE_OAUTH_TOKEN is not set. Run `claude setup-token` and export it (arch §3).",
    };
  }
  if (isSet(env.ANTHROPIC_API_KEY)) {
    return {
      subscriptionAuthOk: false,
      reason:
        "ANTHROPIC_API_KEY is set — it outranks the OAuth token and would meter billing per-token. Unset it (arch §3).",
    };
  }
  if (isSet(env.ANTHROPIC_AUTH_TOKEN)) {
    return {
      subscriptionAuthOk: false,
      reason:
        "ANTHROPIC_AUTH_TOKEN is set — it outranks the OAuth token. Unset it (arch §3).",
    };
  }
  return { subscriptionAuthOk: true };
}

/** Hard gate for `main.ts`: refuse to start if the §3 invariant is violated. */
export function assertSubscriptionAuth(env: Record<string, string | undefined> = process.env): void {
  const status = checkAuth(env);
  if (!status.subscriptionAuthOk) {
    console.error(`[anvild] FATAL — auth/billing guard (arch §3): ${status.reason}`);
    process.exit(1);
  }
}
