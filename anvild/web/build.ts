/** Build the web client into web/dist. Run: bun run build:web */
import { cpSync, mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";

const root = import.meta.dir; // anvild/web
const dist = join(root, "dist");
rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });

const result = await Bun.build({
  entrypoints: [join(root, "src/main.ts")],
  outdir: dist,
  target: "browser",
  format: "esm",
  splitting: true, // mermaid loads as a lazy chunk
  minify: true,
  sourcemap: "linked",
});
if (!result.success) {
  for (const log of result.logs) console.error(log);
  process.exit(1);
}

cpSync(join(root, "index.html"), join(dist, "index.html"));
cpSync(join(root, "styles/app.css"), join(dist, "app.css"));
cpSync(join(root, "sw.js"), join(dist, "sw.js")); // service worker (web push)
cpSync(join(root, "manifest.json"), join(dist, "manifest.json"));
cpSync(join(root, "assets/anvil.svg"), join(dist, "anvil.svg")); // brand mark

// KaTeX stylesheet + fonts (math is server-rendered to HTML+MathML; the client just styles it)
const katex = join(root, "../node_modules/katex/dist");
mkdirSync(join(dist, "katex/fonts"), { recursive: true });
cpSync(join(katex, "katex.min.css"), join(dist, "katex/katex.min.css"));
cpSync(join(katex, "fonts"), join(dist, "katex/fonts"), { recursive: true });

// xterm.js stylesheet (terminal)
cpSync(join(root, "../node_modules/@xterm/xterm/css/xterm.css"), join(dist, "xterm.css"));
// Material Symbols: web loads the font from Google's CDN (index.html); the `material-symbols`
// dep stays installed so the native client apps can bundle the woff2 offline.

console.log(`built web client → ${dist}`);
