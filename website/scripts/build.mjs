import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { escapeHtml, loadCatalog, render, renderMaster, renderComponent } from "./catalog.mjs";
import { loadProject } from "./content.mjs";
import { renderSite } from "../src/content-model.js";
import { catalogPartials } from "../src/render.js";

const website = fileURLToPath(new URL("../", import.meta.url));
const source = path.join(website, "src");
export async function generate(development = false) {
const output = path.join(website, development ? ".preview" : "dist");
const catalog = await loadCatalog(source);
const partials = catalogPartials(catalog);
await mkdir(path.join(output, "assets"), { recursive: true });
await mkdir(path.join(output, "examples"), { recursive: true });
await mkdir(path.join(output, "components"), { recursive: true });

await cp(path.join(source, "assets"), path.join(output, "assets"), { recursive: true });
const rendererLicenses = await Promise.all([
  ["Mustach 1.2.10 (0BSD)", "../shared/renderer/vendor/mustach/LICENSE.txt"],
  ["cJSON 1.7.19 (MIT)", "../shared/renderer/vendor/cjson/LICENSE"],
  ["Lexbor 2.5.0 (Apache-2.0)", "../shared/renderer/vendor/lexbor/LICENSE"],
  ["Lexbor notices", "../shared/renderer/vendor/lexbor/NOTICE"],
  ["utf8proc 2.10.0 (MIT and Unicode data notices)", "../shared/renderer/vendor/utf8proc/LICENSE.md"],
].map(async ([name, file]) => `${name}\n${await readFile(path.join(website, file), "utf8")}`));
await writeFile(path.join(output, "renderer-licenses.txt"), rendererLicenses.join("\n"));

function document(title, content, prefix = "../") {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Webeasy template and component examples">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; media-src 'self'; base-uri 'self'; object-src 'none'; connect-src ${development ? "'self' ws:" : "'none'"}">
  <title>${escapeHtml(title)} | Webeasy</title>
  <base href="${prefix}">
  <link rel="stylesheet" href="assets/site.css">
  <script defer src="assets/site.js"></script>
</head>
<body>
  <main class="center stack site-shell" data-disclosures>
    <nav class="cluster" aria-label="Examples"><a href="editor.html">Editor</a><a href="index.html">Templates</a><a href="showcase.html">Building blocks</a><a href="home.html">Example site</a><a href="news/">News</a></nav>
    ${content}
    <footer><p>Generated on your device. Your content, your hosting.</p></footer>
  </main>
</body>
</html>`;
}

const cards = (entries) => entries.map((entry) =>
  `<a class="box stack card" href="${entry.preview}"><h2>${escapeHtml(entry.title)}</h2><p>${escapeHtml(entry.description)}</p></a>`,
).join("\n");

for (const component of catalog.components) {
  component.preview = `components/${component.id}.html`;
  const example = renderComponent(component, new Set(), partials);
  await writeFile(path.join(output, component.preview), document(component.title,
    `<header class="stack"><h1>${escapeHtml(component.title)}</h1><p>${escapeHtml(component.description)}</p></header>
    ${example}`));
}
for (const template of catalog.templates) {
  template.preview = `examples/${template.id}.html`;
  const usedIds = new Set();
  const content = template.example.blocks.map((id) => {
    const component = catalog.components.find((item) => item.id === id);
    return renderComponent(component, usedIds, partials);
  }).join("\n");
  await writeFile(path.join(output, template.preview),
    template.id === "master"
      ? renderMaster(template.source, { ...template.example, baseHref: "../" }, content, "<nav>Example menu</nav>", partials)
      : document(template.title, render(template.source, template.example, content, partials)));
}
await writeFile(path.join(output, "index.html"), document("Templates",
  `<header class="stack"><p class="eyebrow">Webeasy</p><h1>Small pages. Yours to publish.</h1><p>Explore every page template and the content blocks your app will insert.</p></header>
  <section class="stack"><h2>Page templates</h2><div class="grid">${cards(catalog.templates)}</div></section>`, "./"));
await writeFile(path.join(output, "showcase.html"), document("Component showcase",
  `<header class="stack"><h1>Ready-made building blocks</h1><p>Choose specific content blocks, not layout primitives. Each block has a live example.</p></header>
  <div class="grid">${cards(catalog.components)}</div>`, "./"));
await writeFile(path.join(output, "catalog.json"), `${JSON.stringify(catalog, null, 2)}\n`);
const project = await loadProject(source);
const published = renderSite(project, catalog, { homeHref: "home.html" });
for (const file of published) {
  const destination = path.join(output, file.path === "index.html" ? "home.html" : file.path);
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, file.html);
}
await writeFile(path.join(output, "content.json"), `${JSON.stringify(project, null, 2)}\n`);
await cp(path.join(website, "../shared/storage.sql"), path.join(output, "storage.sql"));
await cp(path.join(source, "editor.html"), path.join(output, "editor.html"));
await cp(path.join(source, "field.html"), path.join(output, "field.html"));
await cp(path.join(source, "generator.html"), path.join(output, "generator.html"));
if (development) {
  await writeFile(path.join(output, "provision.html"), await readFile(path.join(source, "provision.html")));
}
console.log(`Built ${catalog.templates.length} templates and ${catalog.components.length} components.`);
}
