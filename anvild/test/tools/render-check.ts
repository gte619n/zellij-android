/** Throwaway: confirm the daemon emits real pipeline HTML (Shiki/data-line), not passthrough. */
export {};
const url = process.argv[2] ?? "ws://localhost:7701/ws";
const cwd = process.argv[3] ?? process.cwd();
const stamp = (o: object) => JSON.stringify({ v: 1, ts: new Date().toISOString(), ...o });
const ws = new WebSocket(url);
let sid = "";
ws.onopen = () => ws.send(stamp({ type: "session.create", cid: "c", source: "existing-dir", cwd, model: "sonnet" }));
ws.onmessage = (ev) => {
  const m = JSON.parse(String((ev as MessageEvent).data));
  if (m.type === "session.created") {
    sid = m.session.id;
    ws.send(stamp({ type: "prompt.send", cid: "p", sessionId: sid, text: "# Demo\n\n```ts\nconst x: number = 1\n```" }));
  } else if (m.type === "message.user") {
    const html: string = m.rendered.html;
    console.log("message.user → shiki:", html.includes("shiki"), "| data-line:", html.includes("data-line"), "| passthrough:", html.includes("md-raw"));
    console.log(html.slice(0, 320));
    ws.send(stamp({ type: "session.kill", cid: "k", sessionId: sid }));
    setTimeout(() => process.exit(0), 300);
  }
};
setTimeout(() => process.exit(1), 60000);
