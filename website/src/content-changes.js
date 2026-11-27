const fields = {
  Page: { title: "Title", subtitle: "Secondary title", author: "Author", menuTitle: "Menu title", templateId: "Layout", slug: "Slug", date: "Date", coverMediaId: "Cover image", coverAlt: "Cover alternative text", content: "Content" },
  News: { title: "Title", slug: "Slug", date: "Date", coverMediaId: "Cover image", coverAlt: "Cover alternative text", shortContent: "Short content", content: "Long content" },
  Section: { heading: "Heading", subheading: "Subheading", content: "Content" },
  Aside: { heading: "Heading", content: "Content" },
  Media: { filename: "Filename", mimeType: "Media type", originalName: "Original name", size: "Size in bytes" },
};

const value = (record, key) => String(record?.[key] ?? (key === "templateId" ? "page" : ""));
const name = (record) => record.title || record.heading || record.filename || "Untitled";

export function projectChanges(before, after) {
  const changes = [];
  function compare(oldItems = [], newItems = [], kind, parent = "") {
    const oldById = new Map(oldItems.map((item) => [item.id, item]));
    const newById = new Map(newItems.map((item) => [item.id, item]));
    const commonBefore = oldItems.filter((item) => newById.has(item.id));
    const commonAfter = newItems.filter((item) => oldById.has(item.id));
    if (!["News", "Media"].includes(kind) &&
        commonBefore.some((item, index) => item.id !== commonAfter[index].id)) {
      changes.push({
        id: `${parent}/${kind}/order`, title: `${parent ? `${parent} / ` : ""}${kind} order`, status: "Changed",
        fields: [{ name: "Order", before: oldItems.map(name).join("\n"), after: newItems.map(name).join("\n") }],
      });
    }
    for (const id of new Set([...newById.keys(), ...oldById.keys()])) {
      const oldItem = oldById.get(id), newItem = newById.get(id);
      const title = `${parent ? `${parent} / ` : ""}${kind}: ${name(newItem ?? oldItem)}`;
      const summary = (kind === "Page" || kind === "News") && (!oldItem || !newItem);
      const changed = Object.entries(summary ? { title: "Title", date: "Date" } : fields[kind]).flatMap(([key, label]) => {
        const previous = oldItem ? (kind === "News" && key === "content" && oldItem.content === undefined ? value(oldItem, "shortContent") : value(oldItem, key)) : "";
        const current = newItem ? (kind === "News" && key === "content" && newItem.content === undefined ? value(newItem, "shortContent") : value(newItem, key)) : "";
        return !summary && previous === current ? [] : [{ name: label, before: previous, after: current }];
      });
      if (changed.length || !oldItem || !newItem) {
        changes.push({ id, kind, title, status: !oldItem ? "Added" : !newItem ? "Removed" : "Changed", fields: changed });
      }
      if (kind === "Page") compare(oldItem?.children, newItem?.children, "Page", title);
      if (!summary) {
        if (kind === "Page" || kind === "News") compare(oldItem?.sections, newItem?.sections, "Section", title);
        if (kind !== "Aside" && kind !== "Media") compare(oldItem?.asides, newItem?.asides, "Aside", title);
      }
    }
  }
  const home = (project) => project.homePageId ?? project.pages[0]?.id ?? null;
  const pageName = (project, id) => {
    const find = (pages) => pages.flatMap((page) => [page, ...find(page.children ?? [])]);
    return find(project.pages).find((page) => page.id === id)?.title ?? "No homepage";
  };
  if (home(before) !== home(after)) {
    changes.push({
      id: "project/homepage", title: "Homepage", status: "Changed",
      fields: [{ name: "Page", before: pageName(before, home(before)), after: pageName(after, home(after)) }],
    });
  }
  compare(before.pages, after.pages, "Page");
  compare(before.news, after.news, "News");
  compare(before.media, after.media, "Media");
  return changes;
}
