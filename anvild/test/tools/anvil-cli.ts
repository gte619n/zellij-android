/**
 * Tiny scriptable WS client for driving anvild by hand or in CI.
 *
 *   bun run test/tools/anvil-cli.ts [ws-url] < commands.ndjson
 *
 * Reads newline-delimited JSON commands from stdin, stamps each with `v`/`ts` if missing,
 * sends them in order, and prints every server event received. Exits ~500ms after the
 * last line. Default url: ws://localhost:7701/ws
 */
import { PROTOCOL_VERSION } from "@protocol";

const url = process.argv[2] ?? "ws://localhost:7701/ws";
const ws = new WebSocket(url);

ws.addEventListener("message", (ev) => {
  console.log("←", (ev as MessageEvent).data);
});
ws.addEventListener("error", (e) => {
  console.error("ws error:", e);
  process.exit(1);
});

await new Promise<void>((resolve) => ws.addEventListener("open", () => resolve()));

const input = await Bun.stdin.text();
for (const line of input.split("\n")) {
  const trimmed = line.trim();
  if (!trimmed) continue;
  const cmd = { v: PROTOCOL_VERSION, ts: new Date().toISOString(), ...JSON.parse(trimmed) };
  console.log("→", JSON.stringify(cmd));
  ws.send(JSON.stringify(cmd));
  await new Promise((r) => setTimeout(r, 100));
}

await new Promise((r) => setTimeout(r, 500));
ws.close();
