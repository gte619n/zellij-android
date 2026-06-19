import { loadConfig } from "./config";
import { assertSubscriptionAuth } from "./auth/guard";
import { createServer, VERSION } from "./server/http";
import { createMarkdownRenderer } from "./render/markdown-pipeline";

// arch §3: refuse to start unless the subscription-auth invariant holds.
assertSubscriptionAuth();

const config = loadConfig();
const renderer = await createMarkdownRenderer(); // loads Shiki grammars once at startup
const { port } = createServer({
  host: config.host,
  port: config.port,
  stateDir: config.stateDir,
  warnFraction: config.warnFraction,
  softStopFraction: config.softStopFraction,
  renderer,
});

console.log(
  `[anvild ${VERSION}] listening on http://localhost:${port}  ` +
    `(ws: /ws · health: /api/health)`,
);
