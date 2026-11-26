import { uniqueHeadingId } from "./heading-id.js";

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

export function render(source, values, content) {
  return renderSlots(source, values, { content });
}

export function renderMaster(source, values, content, navigation) {
  return renderSlots(source, values, { content, navigation });
}

function renderSlots(source, values, slots) {
  return source.replace(/\{\{\{(\w+)\}\}\}|\{\{(\w+)\}\}/g, (_, raw, key) => {
    if (raw) {
      if (!Object.hasOwn(slots, raw) || typeof slots[raw] !== "string") throw new Error(`Unsupported raw slot: ${raw}`);
      return slots[raw];
    }
    if (!Object.hasOwn(values, key) || typeof values[key] !== "string") {
      throw new Error(`Missing string field: ${key}`);
    }
    return escapeHtml(values[key]);
  });
}

export function renderSection(source, values, content, usedIds = new Set()) {
  if (typeof values.heading !== "string") throw new Error("Missing string field: heading");
  const subheading = values.subheading ?? "";
  if (typeof subheading !== "string") throw new Error("Missing string field: subheading");
  const headingId = uniqueHeadingId(values.heading, usedIds);
  const header = `<header class="stack"><h2 id="${headingId}">${escapeHtml(values.heading)}</h2>${
    subheading.trim() ? `<p>${escapeHtml(subheading)}</p>` : ""}</header>`;
  return render(source, { ...values, headingId }, header + content);
}
