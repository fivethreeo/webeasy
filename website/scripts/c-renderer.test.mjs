import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { renderRequest } from "../src/c-renderer.js";
import { prepareSite, renderSite } from "../src/content-model.js";
import { loadCatalog } from "./catalog.mjs";
import { loadProject } from "./content.mjs";
import { loadTemplateSources, templateSources } from "../src/template-sources.js";

const root = fileURLToPath(new URL("../../", import.meta.url));
const source = path.join(root, "shared/renderer");
const binary = path.join(root, "dist/renderer/native-test");
await mkdir(path.dirname(binary), { recursive: true });
const build = spawnSync(process.env.CC || "cc", [
  "-std=c11", "-DNO_OPEN_MEMSTREAM", "-Wall", "-Wextra", "-Werror",
  ...["test-main.c", "renderer.c", "vendor/mustach/mustach.c", "vendor/cjson/cJSON.c"].map((file) => path.join(source, file)),
  "-lm", "-o", binary,
], { encoding: "utf8" });
assert.equal(build.status, 0, build.error?.message ?? build.stdout + build.stderr);

function native(input) {
  const result = spawnSync(binary, [], {
    input: typeof input === "string" ? input : JSON.stringify(input), encoding: "utf8", maxBuffer: 32 * 1024 * 1024,
  });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}
const request = (source, values, partials = {}) => ({
  partials: { entry: source, ...partials }, rawSlots: ["content", "html"],
  documents: [{ path: "index.html", template: "entry", values }],
});

test("native C and browser WASM share loops, conditionals, nested partials, UTF-8 and safe escaping", () => {
  const input = request(
    "{{#pages}}{{>item}}{{/pages}}{{^pages}}Empty{{/pages}}",
    { site: { title: "Coast" }, pages: [
      { title: "<Ø & 🌊 ' \" >", tags: ["one", "two"] }, { title: "Next", tags: [] },
    ] },
    { item: "<p>{{site.title}}:{{title}}{{#tags}}[{{.}}]{{/tags}}{{^tags}}No tags{{/tags}}</p>" },
  );
  const expected = "<p>Coast:&lt;Ø &amp; 🌊 &#39; &quot; &gt;[one][two]</p><p>Coast:NextNo tags</p>";
  assert.equal(renderRequest(input)[0].html, expected);
  assert.deepEqual(native(input).files, renderRequest(input));
  assert.equal(renderRequest(request("{{^pages}}Empty{{/pages}}", { pages: [] }))[0].html, "Empty");
});

test("native C and WASM reject unsafe raw fields, unknown partials, bad templates and invalid requests", () => {
  for (const [input, error] of [
    [request("{{{title}}}", { title: "<script>" }), /Unsupported raw slot/],
    [request("{{&title}}", { title: "<script>" }), /Unsupported raw slot/],
    [request("{{missing}}", {}), /Missing string field/],
    [request("{{>../../private}}", {}), /Template is missing/],
    [request("{{#items}}", { items: [] }), /Invalid template/],
    [request("{{>loop}}", {}, { loop: "{{>loop}}" }), /Invalid template/],
    [{}, /Invalid render request/],
  ]) {
    assert.throws(() => renderRequest(input), error);
    assert.match(native(input).error, error);
  }
  assert.match(native("{invalid").error, /Invalid render request/);
  const input = request("{{{content}}}", { content: { $render: "rich", values: { html: "<p>Body</p>" } } }, { rich: "{{{html}}}" });
  assert.equal(renderRequest(input)[0].html, "<p>Body</p>");
  assert.deepEqual(native(input).files, renderRequest(input));
});

test("native C and WASM produce identical complete websites without changing project data", async () => {
  const catalog = await loadCatalog(path.join(root, "website/src"));
  const project = await loadProject(path.join(root, "website/src"));
  const before = JSON.stringify(project);
  const input = prepareSite(project, catalog, { publicPathPrefix: "/host/site/" });
  assert.deepEqual(native(input).files, renderSite(project, catalog, { publicPathPrefix: "/host/site/" }));
  assert.equal(JSON.stringify(project), before);
});

test("templates control navigation, footer, headings, sidebars, covers and news without renderer HTML", async () => {
  const catalog = await loadCatalog(path.join(root, "website/src"));
  const project = await loadProject(path.join(root, "website/src"));
  const sources = templateSources(catalog);
  for (const name of ["site-header", "breadcrumbs", "subpage-navigation", "with-asides", "section", "aside", "news-summary", "cover-image", "rich-content"]) {
    const key = `webeasy_templates/components/${name}.html`;
    sources[key] = `<div data-template="${name}">${sources[key]}</div>`;
  }
  sources["webeasy_templates/components/site-footer.html"] = "<footer>Custom footer</footer>";
  const updated = loadTemplateSources(catalog, sources);
  const files = renderSite(project, updated);
  const page = new JSDOM(files.find((file) => file.path === "about/index.html").html).window.document;
  for (const name of ["site-header", "subpage-navigation", "with-asides", "section", "aside", "rich-content"]) {
    assert.ok(page.querySelector(`[data-template="${name}"]`), name);
  }
  for (const file of files) assert.ok(file.html.includes("<footer>Custom footer</footer>"));
  const news = new JSDOM(files.find((file) => file.path === "news/index.html").html).window.document;
  assert.equal(news.querySelectorAll('[data-template="news-summary"]').length, 3);
  sources["webeasy_templates/templates/page.html"] =
    "<h1>{{title}}</h1>{{#sections}}<section data-custom><h2>{{heading}}</h2>{{{html}}}{{#asides}}<p>{{heading}}</p>{{/asides}}</section>{{/sections}}";
  sources["webeasy_templates/templates/news-index.html"] =
    "<h1>All stories</h1>{{#allItems}}{{>news-summary}}{{/allItems}}";
  const structured = loadTemplateSources(catalog, sources);
  const changed = renderSite(project, structured);
  const custom = new JSDOM(changed.find((file) => file.path === "about/index.html").html).window.document;
  assert.ok(custom.querySelector("section[data-custom] h2"));
  const allNews = new JSDOM(changed.find((file) => file.path === "news/index.html").html).window.document;
  assert.equal(allNews.querySelectorAll("article").length, project.news.length);
});
