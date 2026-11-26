import { render, renderMaster, renderSection } from "./render.js";

export const templateDirectory = "webeasy_templates";
export const maximumTemplateBytes = 256 * 1024;

export function templateSources(catalog) {
  const sources = {};
  for (const group of ["templates", "components"]) {
    for (const entry of catalog[group]) {
      if (!/^[a-z][a-z0-9-]*$/.test(entry.id) || typeof entry.source !== "string") {
        throw new Error("Invalid template catalog.");
      }
      sources[`${templateDirectory}/${group}/${entry.id}.html`] = entry.source;
    }
  }
  return sources;
}

export function loadTemplateSources(catalog, sources) {
  if (!sources || typeof sources !== "object" || Array.isArray(sources)) throw new Error("Invalid template files.");
  const expected = templateSources(catalog);
  if (Object.keys(sources).length !== Object.keys(expected).length ||
      Object.keys(sources).some((path) => !Object.hasOwn(expected, path))) throw new Error("Unexpected template files.");
  const updated = JSON.parse(JSON.stringify(catalog));
  for (const group of ["templates", "components"]) {
    for (const entry of updated[group]) {
      const source = sources[`${templateDirectory}/${group}/${entry.id}.html`];
      if (typeof source !== "string" || !source.trim() || new TextEncoder().encode(source).length > maximumTemplateBytes) {
        throw new Error(`Missing, empty or oversized template: ${entry.id}`);
      }
      if (!source.includes("{{{content}}}") && group === "templates") {
        throw new Error(`Template must retain {{{content}}}: ${entry.id}`);
      }
      entry.source = source;
    }
  }
  for (const entry of updated.components) {
    if (entry.id === "section") renderSection(entry.source, entry.example, entry.example.content);
    else render(entry.source, entry.example, entry.example.content);
  }
  for (const entry of updated.templates) {
    const content = entry.example.blocks.map((id) => {
      const component = updated.components.find((item) => item.id === id);
      if (!component) throw new Error(`Missing example component: ${id}`);
      return component.id === "section"
        ? renderSection(component.source, component.example, component.example.content)
        : render(component.source, component.example, component.example.content);
    }).join("\n");
    if (entry.id === "master") renderMaster(entry.source, entry.example, content, "<nav>Example menu</nav>");
    else render(entry.source, entry.example, content);
  }
  return updated;
}

export function templatePreview(catalog, id) {
  const entry = catalog.templates.find((item) => item.id === id);
  if (!entry) throw new Error("Template is missing.");
  const usedIds = new Set();
  const content = entry.example.blocks.map((block) => {
    const component = catalog.components.find((item) => item.id === block);
    return component.id === "section"
      ? renderSection(component.source, component.example, component.example.content, usedIds)
      : render(component.source, component.example, component.example.content);
  }).join("\n");
  const master = catalog.templates.find((item) => item.id === "master");
  const body = id === "master" ? content : render(entry.source, entry.example, content);
  return renderMaster(master.source, { title: entry.title, baseHref: "./" }, body, "<nav>Example menu</nav>");
}
