import { test, expect, beforeAll } from "bun:test";
import { createMarkdownRenderer } from "../../src/render/markdown-pipeline";
import type { MarkdownRenderer } from "../../src/render/markdown";

let r: MarkdownRenderer;
beforeAll(async () => {
  r = await createMarkdownRenderer();
});

test("preserves source verbatim", () => {
  const src = "# Title\n\nbody";
  expect(r.render(src).source).toBe(src);
});

test("headings & paragraphs carry data-line", () => {
  const html = r.render("# Hello\n\nA paragraph.").html;
  expect(html).toContain("<h1");
  expect(html).toContain('data-line="0,1"'); // heading at line 0
  expect(html).toMatch(/<p[^>]*data-line=/);
});

test("fenced code is Shiki-highlighted with data-line", () => {
  const html = r.render("```ts\nconst x: number = 1\n```").html;
  expect(html).toContain("shiki");
  expect(html).toContain("data-line=");
  expect(html).toContain("const"); // token text survives
});

test("mermaid stays inert (not executed/rendered to SVG)", () => {
  const html = r.render("```mermaid\ngraph TD; A-->B;\n```").html;
  expect(html).toContain('class="mermaid"');
  expect(html).toContain("graph TD"); // raw source preserved for the WebView
  expect(html).not.toContain("<svg");
});

test("GFM tables render", () => {
  const html = r.render("| a | b |\n|---|---|\n| 1 | 2 |").html;
  expect(html).toContain("<table");
  expect(html).toContain("<td");
});

test("inline math renders via KaTeX", () => {
  const html = r.render("Euler: $e^{i\\pi}+1=0$").html;
  expect(html).toContain("katex");
});

test("no executable script element or javascript: href is produced", () => {
  const html = r.render("<script>alert(1)</script>\n\n[click](javascript:alert(1))").html;
  expect(html).not.toContain("<script"); // no script ELEMENT (raw HTML was escaped)
  expect(html.toLowerCase()).not.toContain('href="javascript'); // markdown-it drops the dangerous href
});

test("raw HTML in source is escaped, not emitted as a live element", () => {
  const html = r.render("<img src=x onerror=alert(1)>").html;
  expect(html).not.toContain("<img"); // no real <img> tag — escaped to text inside <p>
  expect(html).toContain("&lt;img"); // present only as escaped text
});
