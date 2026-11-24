import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, cp, readFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadCatalog, render } from "./catalog.mjs";

const source = fileURLToPath(new URL("../src", import.meta.url));

test("text and attributes are escaped; only the generated content slot accepts HTML", () => {
  assert.equal(render('<p title="{{title}}">{{title}}</p>{{{content}}}',
    { title: '<script>"&\'' }, "<section>Safe generated block</section>"),
  '<p title="&lt;script&gt;&quot;&amp;&#39;">&lt;script&gt;&quot;&amp;&#39;</p><section>Safe generated block</section>');
  assert.throws(() => render("{{{title}}}", { title: "raw" }), /Unsupported raw slot/);
  assert.throws(() => render("{{missing}}", {}), /Missing string field/);
});

test("every defined template and component has a renderable example", async () => {
  const catalog = await loadCatalog(source);
  for (const entry of [...catalog.templates, ...catalog.components]) {
    assert.ok(entry.source);
    assert.ok(entry.example);
  }
});

test("new templates are discovered from the catalog and invalid entries fail early", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "webeasy-catalog-"));
  try {
    await cp(source, directory, { recursive: true });
    const catalog = JSON.parse(await readFile(path.join(directory, "catalog.json"), "utf8"));
    catalog.templates.push({ ...catalog.templates[0], id: "new-page", file: "templates/new-page.html" });
    await cp(path.join(directory, "templates/page.html"), path.join(directory, "templates/new-page.html"));
    await writeFile(path.join(directory, "catalog.json"), JSON.stringify(catalog));
    assert.equal((await loadCatalog(directory)).templates.at(-1).id, "new-page");
    catalog.templates.at(-1).example = { ...catalog.templates[0].example, blocks: ["missing"] };
    await writeFile(path.join(directory, "catalog.json"), JSON.stringify(catalog));
    await assert.rejects(loadCatalog(directory), /Unknown component/);
    catalog.templates.at(-1).file = "../../outside.html";
    await writeFile(path.join(directory, "catalog.json"), JSON.stringify(catalog));
    await assert.rejects(loadCatalog(directory), /Unexpected source path/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
