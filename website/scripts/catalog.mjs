import { readFile } from "node:fs/promises";
import path from "node:path";
import { catalogPartials, render, renderMaster, renderSection, renderCatalog } from "../src/render.js";
export { escapeHtml, render, renderMaster } from "../src/render.js";

export function renderComponent(component, usedIds = new Set(), partials = {}) {
  return component.id === "section"
    ? renderSection(component.source, component.example, component.example.content, usedIds, partials)
    : renderCatalog(component.source, component.example, partials);
}

export async function loadCatalog(root) {
  const catalog = JSON.parse(await readFile(path.join(root, "catalog.json"), "utf8"));
  if (catalog.version !== 1) throw new Error("Unsupported catalog version");
  for (const [group, directory] of [["templates", "templates"], ["components", "components"]]) {
    if (!Array.isArray(catalog[group]) || !catalog[group].length) {
      throw new Error(`Catalog must contain ${group}`);
    }
    const ids = new Set();
    for (const entry of catalog[group]) {
      if (!/^[a-z][a-z0-9-]*$/.test(entry.id) || ids.has(entry.id)) {
        throw new Error(`Invalid or duplicate ${group} ID: ${entry.id}`);
      }
      ids.add(entry.id);
      if (entry.pageLayout !== undefined && (group !== "templates" || typeof entry.pageLayout !== "boolean")) {
        throw new Error(`Invalid page layout flag: ${entry.id}`);
      }
      if (entry.file !== `${directory}/${entry.id}.html`) {
        throw new Error(`Unexpected source path: ${entry.file}`);
      }
      if (typeof entry.title !== "string" || typeof entry.description !== "string" ||
          !entry.example || typeof entry.example !== "object" || Array.isArray(entry.example)) {
        throw new Error(`Incomplete entry: ${entry.id}`);
      }
      entry.source = await readFile(path.join(root, entry.file), "utf8");
    }
  }
  const partials = catalogPartials(catalog);
  for (const component of catalog.components) {
    renderComponent(component, new Set(), partials);
    for (const [key, value] of Object.entries(component.example)) {
      if (["src", "poster"].includes(key) &&
          (typeof value !== "string" || !/^assets\/[a-zA-Z0-9/_-]+\.[a-zA-Z0-9]+$/.test(value) ||
           value.split("/").includes(".."))) {
        throw new Error(`Invalid bundled media path: ${value}`);
      }
      if (["src", "poster"].includes(key)) await readFile(path.join(root, value));
    }
  }
  for (const template of catalog.templates) {
    if (!Array.isArray(template.example.blocks) || !template.example.blocks.length) {
      throw new Error(`Template needs example blocks: ${template.id}`);
    }
    const usedIds = new Set();
    const content = template.example.blocks.map((id) => {
      const component = catalog.components.find((item) => item.id === id);
      if (!component) throw new Error(`Unknown component: ${id}`);
      return renderComponent(component, usedIds, partials);
    }).join("\n");
    if (template.id === "master") renderMaster(template.source, template.example, content, "<nav>Example menu</nav>", partials);
    else render(template.source, template.example, content, partials);
  }
  return catalog;
}
