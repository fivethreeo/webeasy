import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { loadCatalog } from "./catalog.mjs";
import { loadProject } from "./content.mjs";
import { renderSite } from "../src/content-model.js";
import { loadTemplateSources, templateSources, templatePreview } from "../src/template-sources.js";
import { render, renderMaster } from "../src/render.js";

const root = fileURLToPath(new URL("../src", import.meta.url));
const catalog = await loadCatalog(root);
const project = await loadProject(root);

test("server source files include the master and every template/component without rendered content", () => {
  const sources = templateSources(catalog);
  assert.equal(sources["webeasy_templates/templates/master.html"], catalog.templates.find((entry) => entry.id === "master").source);
  assert.equal(Object.keys(sources).length, catalog.templates.length + catalog.components.length);
  assert.ok(sources["webeasy_templates/templates/page.html"].includes("{{{content}}}"));
});

test("loading edited sources changes generated pages, news, homepage and template previews without mutating the bundle", () => {
  const sources = templateSources(catalog);
  sources["webeasy_templates/templates/master.html"] = sources["webeasy_templates/templates/master.html"]
    .replace("<body>", '<body data-master="from-server">');
  sources["webeasy_templates/templates/page.html"] = '<div data-page="from-server">{{{content}}}</div>';
  const updated = loadTemplateSources(catalog, sources);
  for (const file of renderSite(project, updated)) {
    assert.equal(new JSDOM(file.html).window.document.body.dataset.master, "from-server", file.path);
    assert.ok(!file.html.includes("{{"), file.path);
  }
  const page = renderSite(project, updated).find((file) => file.path === "about/index.html");
  assert.ok(page.html.includes('data-page="from-server"'));
  assert.ok(templatePreview(updated, "page").includes('data-page="from-server"'));
  assert.ok(templatePreview(updated, "master").includes('data-master="from-server"'));
  assert.ok(!catalog.templates.find((entry) => entry.id === "master").source.includes("from-server"));
});

test("missing, empty, malformed, oversized and unexpected files fail as a complete batch", () => {
  const original = JSON.stringify(catalog);
  for (const modify of [
    (sources) => { delete sources["webeasy_templates/templates/master.html"]; },
    (sources) => { sources["webeasy_templates/templates/page.html"] = ""; },
    (sources) => { sources["webeasy_templates/templates/page.html"] = "{{#sections}}Missing closing section"; },
    (sources) => { sources["webeasy_templates/templates/page.html"] = "{{{title}}}{{{content}}}"; },
    (sources) => { sources["webeasy_templates/templates/page.html"] = `${"é".repeat(131073)}{{{content}}}`; },
    (sources) => { sources["webeasy_templates/templates/page.html"] = "{{unknown}}{{{content}}}"; },
    (sources) => { sources["../private.html"] = "{{{content}}}"; },
  ]) {
    const sources = templateSources(catalog);
    modify(sources);
    assert.throws(() => loadTemplateSources(catalog, sources));
    assert.equal(JSON.stringify(catalog), original);
  }
});

test("only the master renderer accepts generated navigation, and its plain-text slots remain escaped", () => {
  assert.equal(renderMaster("{{title}}{{{navigation}}}{{{content}}}", { title: "<script>" }, "<p>Body</p>", "<nav>Menu</nav>"),
    "&lt;script&gt;<nav>Menu</nav><p>Body</p>");
  assert.throws(() => render("{{{navigation}}}", {}, "<p>Body</p>"), /Unsupported raw slot/);
});
