import { escapeHtml, render } from "./render.js";
import { localMediaPath } from "./rich-text.js";

const reserved = new Set(["news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index"]);

export function newId() {
  return globalThis.crypto?.randomUUID?.() ??
    `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function normalizeProject(input, sanitize) {
  if (input?.version !== 1 || !Array.isArray(input.pages) || !Array.isArray(input.news)) {
    throw new Error("Unsupported content project.");
  }
  const ids = new Set();
  const text = (record, field, required = false) => {
    if (typeof record[field] !== "string" || (required && !record[field].trim())) {
      throw new Error(`Missing ${field}.`);
    }
    return record[field];
  };
  const identity = (record) => {
    const id = text(record, "id", true);
    if (!/^[a-zA-Z0-9_-]+$/.test(id) || ids.has(id)) throw new Error("Content IDs must be unique.");
    ids.add(id);
    return id;
  };
  const list = (record, field) => {
    if (record[field] === undefined) return [];
    if (!Array.isArray(record[field])) throw new Error(`${field} must be an ordered list.`);
    return record[field];
  };
  const slug = (record) => {
    const value = text(record, "slug", true);
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value)) throw new Error("Slugs use lowercase letters, numbers, and hyphens.");
    return value;
  };
  const date = (record) => {
    const value = text(record, "date", true);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) ||
        !Number.isFinite(Date.parse(value)) ||
        new Date(value).toISOString().slice(0, 10) !== value) throw new Error("Dates must be valid YYYY-MM-DD dates.");
    return value;
  };
  const asides = (record) => list(record, "asides").map((aside) => ({
    id: identity(aside),
    heading: aside.heading === undefined ? "" : text(aside, "heading"),
    content: sanitize(text(aside, "content")),
  }));
  const sections = (record) => list(record, "sections").map((section) => ({
    id: identity(section), heading: text(section, "heading", true),
    content: sanitize(text(section, "content")), asides: asides(section),
  }));
  const pages = (records, root = false) => {
    const slugs = new Set();
    return records.map((page) => {
      const pageSlug = slug(page);
      if (slugs.has(pageSlug) || (root && reserved.has(pageSlug))) throw new Error(`Duplicate or reserved page slug: ${pageSlug}`);
      slugs.add(pageSlug);
      const templateId = page.templateId === undefined ? "page" : text(page, "templateId", true);
      if (!/^[a-z][a-z0-9-]*$/.test(templateId)) throw new Error("Invalid page layout ID.");
      return {
        id: identity(page), title: text(page, "title", true), menuTitle: text(page, "menuTitle", true),
        templateId, slug: pageSlug, content: sanitize(text(page, "content")), date: date(page),
        sections: sections(page), asides: asides(page), children: pages(list(page, "children")),
      };
    });
  };
  const normalizedPages = pages(input.pages, true);
  const newsRoutes = new Set();
  const news = input.news.map((item) => {
    const itemDate = date(item), itemSlug = slug(item), route = `${itemDate}/${itemSlug}`;
    if (newsRoutes.has(route)) throw new Error(`Duplicate news route: ${route}`);
    newsRoutes.add(route);
    return {
      id: identity(item), title: text(item, "title", true), slug: itemSlug, date: itemDate,
      shortContent: sanitize(text(item, "shortContent")), sections: sections(item), asides: asides(item),
    };
  });
  const media = list(input, "media").map((item) => {
    const filename = text(item, "filename", true);
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]+\.(jpg|jpeg|png|gif|webp|mp4|webm|mov|ogg)$/.test(filename)) {
      throw new Error("Invalid managed media filename.");
    }
    if (!Number.isSafeInteger(item.size) || item.size <= 0) throw new Error("Invalid media size.");
    return {
      id: identity(item), filename, mimeType: text(item, "mimeType", true),
      originalName: text(item, "originalName"), size: item.size,
    };
  });
  if (new Set(media.map((item) => item.filename)).size !== media.length) throw new Error("Duplicate media filename.");
  return { version: 1, pages: normalizedPages, news, media };
}

export function pageRoutes(pages, ancestors = []) {
  return pages.flatMap((page) => {
    const lineage = [...ancestors, page];
    const route = `/${lineage.map((item) => item.slug).join("/")}/`;
    return [{ record: page, route, ancestors }, ...pageRoutes(page.children, lineage)];
  });
}

export function sortedNews(news) {
  return [...news].sort((a, b) => b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug));
}

const href = (route) => route.replace(/^\//, "");
const rich = (html) => `<div class="stack rich-content">${html}</div>`;

function withAsides(content, asides, catalog) {
  if (!asides.length) return content;
  const source = catalog.components.find((item) => item.id === "aside")?.source;
  if (!source) throw new Error("The aside component is missing.");
  return `<div class="with-sidebar with-sidebar:right">
    <div class="stack content-main">${content}</div>
    <div class="stack content-asides">${asides.map((aside) => render(source,
      { heading: aside.heading }, `${aside.heading ? `<h3>${escapeHtml(aside.heading)}</h3>` : ""}${rich(aside.content)}`)).join("\n")}</div>
  </div>`;
}

function body(record, catalog) {
  const source = catalog.components.find((item) => item.id === "section")?.source;
  if (record.sections.length && !source) throw new Error("The section component is missing.");
  const sections = record.sections.map((section) => render(source, { heading: section.heading },
    withAsides(rich(section.content), section.asides, catalog))).join("\n");
  return withAsides(`${rich(record.content ?? record.shortContent)}${sections}`, record.asides, catalog);
}

function menu(pages, prefix = "") {
  return pages.map((page) => {
    const route = `${prefix}${page.slug}/`;
    return `<li><a href="${route}">${escapeHtml(page.menuTitle)}</a>${page.children.length
      ? `<ul>${menu(page.children, route)}</ul>` : ""}</li>`;
  }).join("\n");
}

export function renderSite(project, catalog) {
  const template = (id) => {
    const source = catalog.templates.find((item) => item.id === id)?.source;
    if (!source) throw new Error(`Template is missing: ${id}`);
    return source;
  };
  const pageTemplate = (record) => {
    const id = record.templateId ?? "page";
    if (id !== "page" && !catalog.templates.some((entry) => entry.id === id && entry.pageLayout === true)) {
      throw new Error(`Unavailable page layout: ${id}`);
    }
    return template(id);
  };
  const navigation = `<nav class="site-menu" aria-label="Main menu"><ul class="cluster">${menu(project.pages)}
    <li><a href="news/">News</a></li></ul></nav>`;
  const files = [];
  const document = (title, content, file) => {
    const prefix = "../".repeat(file.split("/").length - 1) || "./";
    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title><base href="${prefix}">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; media-src 'self'; base-uri 'self'; object-src 'none'">
<link rel="stylesheet" href="assets/site.css"><script defer src="assets/site.js"></script>
</head><body><main class="center stack site-shell published-shell">${navigation}${content}</main></body></html>`;
  };
  const pages = pageRoutes(project.pages);
  for (const page of pages) {
    const file = `${href(page.route)}index.html`;
    const breadcrumbs = page.ancestors.length
      ? `<nav aria-label="Breadcrumb"><ol class="cluster">${page.ancestors.map((ancestor, index) =>
        `<li><a href="${page.ancestors.slice(0, index + 1).map((item) => item.slug).join("/")}/">${escapeHtml(ancestor.menuTitle)}</a></li>`).join("")
        }<li>${escapeHtml(page.record.menuTitle)}</li></ol></nav>` : "";
    files.push({ path: file, html: document(page.record.title,
      breadcrumbs + render(pageTemplate(page.record), page.record, body(page.record, catalog)), file) });
  }
  const news = sortedNews(project.news);
  for (const item of news) {
    const file = `news/${item.date}/${item.slug}/index.html`;
    files.push({ path: file, html: document(item.title,
      render(template("news"), item, body(item, catalog)), file) });
  }
  const articles = news.slice(0, 3).map((item) => `<article class="stack news-summary">
    <header class="stack"><h2><a href="news/${item.date}/${item.slug}/">${escapeHtml(item.title)}</a></h2>
    <time datetime="${item.date}">${item.date}</time></header>${rich(item.shortContent)}</article>`).join("\n");
  const listing = render(template("news-index"), {}, articles || "<p>No news yet.</p>");
  files.push({ path: "news/index.html", html: document("News", listing, "news/index.html") });
  const homepage = project.pages.length
    ? render(pageTemplate(project.pages[0]), project.pages[0], body(project.pages[0], catalog)) : listing;
  files.push({ path: "index.html", html: document(project.pages[0]?.title ?? "News", homepage, "index.html") });
  return files;
}

export function referencedMedia(project, window) {
  const paths = new Set();
  const visit = (record) => {
    if (Array.isArray(record)) { record.forEach(visit); return; }
    if (!record || typeof record !== "object") return;
    for (const [key, value] of Object.entries(record)) {
      if (["content", "shortContent"].includes(key) && typeof value === "string") {
        const template = window.document.createElement("template");
        template.innerHTML = value;
        for (const node of template.content.querySelectorAll("img,video,source")) {
          for (const attribute of ["src", "poster"]) {
            const path = node.getAttribute(attribute);
            if (path && localMediaPath(path)) paths.add(path);
          }
        }
      } else if (Array.isArray(value)) value.forEach(visit);
    }
  };
  visit(project);
  return [...paths];
}
