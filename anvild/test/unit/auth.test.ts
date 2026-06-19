import { test, expect } from "bun:test";
import { checkAuth } from "../../src/auth/guard";

test("ok with only the OAuth token", () => {
  expect(checkAuth({ CLAUDE_CODE_OAUTH_TOKEN: "tok" }).subscriptionAuthOk).toBe(true);
});

test("fails when ANTHROPIC_API_KEY is also set (would meter billing)", () => {
  const s = checkAuth({ CLAUDE_CODE_OAUTH_TOKEN: "tok", ANTHROPIC_API_KEY: "sk-…" });
  expect(s.subscriptionAuthOk).toBe(false);
  expect(s.reason).toContain("ANTHROPIC_API_KEY");
});

test("fails when ANTHROPIC_AUTH_TOKEN is set (outranks OAuth)", () => {
  const s = checkAuth({ CLAUDE_CODE_OAUTH_TOKEN: "tok", ANTHROPIC_AUTH_TOKEN: "x" });
  expect(s.subscriptionAuthOk).toBe(false);
});

test("fails when the OAuth token is absent", () => {
  expect(checkAuth({}).subscriptionAuthOk).toBe(false);
});

test("treats a whitespace-only token as unset", () => {
  expect(checkAuth({ CLAUDE_CODE_OAUTH_TOKEN: "   " }).subscriptionAuthOk).toBe(false);
});
