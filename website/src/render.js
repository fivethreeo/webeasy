import { uniqueHeadingId } from "./heading-id.js";
import { renderRequest } from "./c-renderer.js";

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

export function catalogPartials(catalog) {
  const entries = [...catalog.templates, ...catalog.components];
  if (new Set(entries.map((entry) => entry.id)).size !== entries.length) throw new Error("Template IDs must be unique across the catalog.");
  return Object.fromEntries(entries.map((entry) => [entry.id, entry.source]));
}

export function render(source, values, content = "", partials = {}) {
  return renderSlots(source, values, { content, html: values.html ?? content }, partials);
}

export function renderMaster(source, values, content, navigation, partials = {}) {
  return renderSlots(source, values, { content, navigation, html: values.html ?? content }, partials);
}

export function renderCatalog(source, values, partials) {
  return renderRequest({
    partials: { ...partials, _entry: source }, rawSlots: ["content", "navigation", "html"],
    documents: [{ path: "", template: "_entry", values }],
  })[0].html;
}

function renderSlots(source, values, slots, partials) {
  return renderRequest({
    partials: { "site-footer": "", ...partials, _entry: source },
    rawSlots: Object.keys(slots),
    documents: [{ path: "", template: "_entry", values: { ...values, ...slots } }],
  })[0].html;
}

export function renderSection(source, values, content, usedIds = new Set(), partials = {}) {
  if (typeof values.heading !== "string") throw new Error("Missing string field: heading");
  const subheading = values.subheading ?? "";
  if (typeof subheading !== "string") throw new Error("Missing string field: subheading");
  const headingId = uniqueHeadingId(values.heading, usedIds);
  const fields = { ...values, subheading: subheading.trim(), headingId };
  const body = render(partials["section-content"], fields, content, partials);
  return render(source, fields, body, partials);
}
