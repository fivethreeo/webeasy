export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

export function render(source, values, content) {
  return source.replace(/\{\{\{(\w+)\}\}\}|\{\{(\w+)\}\}/g, (_, raw, key) => {
    if (raw) {
      if (raw !== "content" || content === undefined) throw new Error(`Unsupported raw slot: ${raw}`);
      return content;
    }
    if (!Object.hasOwn(values, key) || typeof values[key] !== "string") {
      throw new Error(`Missing string field: ${key}`);
    }
    return escapeHtml(values[key]);
  });
}
