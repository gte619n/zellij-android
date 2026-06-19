import MarkdownIt from "markdown-it";
import texmath from "markdown-it-texmath";
import katex from "katex";
import { createHighlighter } from "shiki";
import createDOMPurify from "dompurify";
import { JSDOM } from "jsdom";
import type { RenderedMarkdown } from "@protocol";
import type { MarkdownRenderer } from "./markdown";

/**
 * The real Markdown → sanitized HTML pipeline (arch §8.3): markdown-it (with `data-line`
 * source attributes) → Shiki code highlighting → KaTeX math → DOMPurify. Produces the
 * `RenderedMarkdown { source, html }` the daemon emits for every markdown surface.
 *
 * `createMarkdownRenderer` is async only because Shiki loads grammars once at startup; the
 * returned `render()` is synchronous, so the emit path (map.ts) stays sync.
 */
const THEMES = { light: "github-light", dark: "github-dark" } as const;
const LANGS = [
  "typescript", "tsx", "javascript", "jsx", "json", "jsonc", "python", "rust", "bash",
  "shell", "yaml", "toml", "markdown", "diff", "html", "css", "sql", "go", "swift",
  "kotlin", "java", "c", "cpp",
];

function escapeHtml(s: string): string {
  return s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

export async function createMarkdownRenderer(): Promise<MarkdownRenderer> {
  const highlighter = await createHighlighter({ themes: [THEMES.light, THEMES.dark], langs: LANGS });
  const loaded = new Set(highlighter.getLoadedLanguages());

  const md = new MarkdownIt({ html: false, linkify: true, typographer: true });

  // data-line on each opening block token — the select-to-cite hook (arch §8.2/§8.3).
  md.core.ruler.push("anvil_data_line", (state) => {
    for (const token of state.tokens) {
      if (token.map && token.nesting === 1) {
        token.attrSet("data-line", `${token.map[0]},${token.map[1]}`);
      }
    }
  });

  // Fenced code → Shiki (dual-theme, CSS-var based). Mermaid stays inert text (rendered in
  // the WebView, arch §8.3). Both carry data-line.
  md.renderer.rules.fence = (tokens, idx) => {
    const token = tokens[idx]!;
    const lang = (token.info.trim().split(/\s+/)[0] || "text").toLowerCase();
    const dataLine = token.map ? ` data-line="${token.map[0]},${token.map[1]}"` : "";
    if (lang === "mermaid") {
      return `<pre class="mermaid"${dataLine}>${escapeHtml(token.content)}</pre>`;
    }
    const useLang = loaded.has(lang) ? lang : "text";
    const html = highlighter.codeToHtml(token.content, { lang: useLang, themes: THEMES });
    return dataLine ? html.replace(/^<pre/, `<pre${dataLine}`) : html;
  };

  // Math via KaTeX. trust:false is mandatory (CVE-2025-23207 is reachable only with trust).
  md.use(texmath, {
    engine: katex,
    delimiters: "dollars",
    katexOptions: { throwOnError: false, strict: "warn", trust: false, output: "htmlAndMathml" },
  });

  const purifier = createDOMPurify(new JSDOM("").window as unknown as Window & typeof globalThis);

  return {
    render(source: string): RenderedMarkdown {
      const rawHtml = md.render(source);
      const html = purifier.sanitize(rawHtml, {
        ADD_ATTR: ["data-line", "style"],
        // KaTeX emits MathML; keep it (DOMPurify allows MathML by default, listed for clarity).
        ADD_TAGS: ["math", "semantics", "annotation", "mrow", "msup", "msub", "mfrac"],
      });
      return { source, html };
    },
  };
}
