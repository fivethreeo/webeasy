import { escapeHtml, render, renderMaster, renderSection } from "./render.js";
import { localMediaPath, safeLinkHref } from "./rich-text.js";
import { normalizePublishingSettings } from "./publishing-settings.js";

const reserved = new Set(["news", "assets", "media", "examples", "components", "editor", "provision", "catalog", "content", "index"]);

export function newId() {
  return globalThis.crypto?.randomUUID?.() ??
    `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function normalizeProject(input, sanitize, sanitizeShort = sanitize) {
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
    subheading: section.subheading === undefined ? "" : text(section, "subheading"),
    content: sanitize(text(section, "content")), asides: asides(section),
  }));
  const coverFields = (record) => {
    const coverMediaId = record.coverMediaId === undefined ? "" : text(record, "coverMediaId");
    if (coverMediaId && !/^[a-zA-Z0-9_-]+$/.test(coverMediaId)) throw new Error("Invalid cover image ID.");
    return { coverMediaId, coverAlt: record.coverAlt === undefined ? "" : text(record, "coverAlt") };
  };
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
        ...coverFields(page),
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
      content: sanitize(item.content === undefined ? text(item, "shortContent") : text(item, "content")),
      shortContent: sanitizeShort(text(item, "shortContent")), sections: sections(item), asides: asides(item),
      ...coverFields(item),
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
  for (const record of [...pageRoutes(normalizedPages).map((entry) => entry.record), ...news]) coverMedia(record, media);
  const homePageId = input.homePageId ?? normalizedPages[0]?.id ?? null;
  if (homePageId !== null && !pageRoutes(normalizedPages).some((entry) => entry.record.id === homePageId)) {
    throw new Error("Choose an existing page for the homepage.");
  }
  return { version: 1, homePageId, pages: normalizedPages, news, media };
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

function coverMedia(record, media = []) {
  if (!record.coverMediaId) return null;
  const image = media.find((item) => item.id === record.coverMediaId);
  if (!image || !image.mimeType.startsWith("image/") || !/\.(jpg|jpeg|png|gif|webp)$/.test(image.filename)) {
    throw new Error("Choose an existing managed image for the cover.");
  }
  return image;
}

function coverMarkup(record, media) {
  const image = coverMedia(record, media);
  return image ? rich(`<figure class="stack cover-image"><img src="media/${escapeHtml(image.filename)}" alt="${escapeHtml(record.coverAlt ?? "")}"></figure>`) : "";
}

function body(record, catalog, media) {
  const source = catalog.components.find((item) => item.id === "section")?.source;
  if (record.sections.length && !source) throw new Error("The section component is missing.");
  const usedIds = new Set();
  const sections = record.sections.map((section) => renderSection(source, section,
    withAsides(rich(section.content), section.asides, catalog), usedIds)).join("\n");
  return coverMarkup(record, media) + withAsides(`${rich(record.content ?? record.shortContent)}${sections}`, record.asides, catalog);
}

function menu(pages, activeId, currentId, homeId) {
  return pages.filter((page) => page.id !== homeId).map((page) => {
    return `<li><a href="${page.slug}/"${page.id === activeId ? ' class="active"' : ""}${page.id === currentId ? ' aria-current="page"' : ""}>${escapeHtml(page.menuTitle)}</a></li>`;
  }).join("\n");
}

function subpageNavigation(entry, homeId) {
  const top = entry.ancestors[0] ?? entry.record;
  if (!top.children.length) return "";
  const active = new Set([...entry.ancestors.map((page) => page.id), entry.record.id]);
  const items = (pages, prefix) => pages.map((page) => {
    const route = `${prefix}${page.slug}/`;
    if (page.id === homeId) return items(page.children, route);
    return `<li><a href="${route}"${active.has(page.id) ? ' class="active"' : ""}${page.id === entry.record.id ? ' aria-current="page"' : ""}>${escapeHtml(page.menuTitle)}</a>${page.children.length ? `<ul class="stack">${items(page.children, route)}</ul>` : ""}</li>`;
  }).join("\n");
  const children = items(top.children, `${top.slug}/`);
  if (!children.trim()) return "";
  return `<aside class="stack box content-asides subpage-navigation"><nav class="stack" aria-label="Subpages">
    <h2>Subpages</h2><ul class="stack">${children}</ul></nav></aside>`;
}

export function renderSite(project, catalog, options = {}) {
  const { publicPathPrefix } = normalizePublishingSettings({ publicPathPrefix: options.publicPathPrefix });
  const homeHref = options.homeHref ?? (publicPathPrefix || "./");
  if (homeHref !== "./" && !safeLinkHref(homeHref)) throw new Error("Invalid website home URL.");
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
  const files = [];
  const pages = pageRoutes(project.pages);
  const homeEntry = pages.find((entry) => entry.record.id === project.homePageId) ?? pages[0];
  const home = homeEntry?.record;
  const pageContent = (entry) => {
    const article = render(pageTemplate(entry.record), entry.record, body(entry.record, catalog, project.media));
    const aside = subpageNavigation(entry, home?.id);
    return aside ? `<div class="with-sidebar with-sidebar:right"><div class="stack content-main">${article}</div>${aside}</div>` : article;
  };
  const document = (title, content, file, currentId = null) => {
    const prefix = "../".repeat(file.split("/").length - 1) || "./";
    const current = pages.find((entry) => entry.record.id === currentId);
    const activeId = current?.ancestors[0]?.id ?? currentId;
    const newsIndex = file === "news/index.html" || (file === "index.html" && !pages.length);
    const newsActive = newsIndex || file.startsWith("news/");
    const navigation = `<header class="cluster site-header">
    <a class="site-logo" href="${escapeHtml(homeHref)}" aria-label="Home"${currentId === home?.id && home ? ' aria-current="page"' : ""}><img src="assets/logo.svg" width="80" height="64" alt=""></a>
    <nav class="site-menu" aria-label="Main menu"><ul class="cluster">${menu(project.pages, activeId, currentId, home?.id)}
    <li><a href="news/"${newsActive ? ' class="active"' : ""}${newsIndex ? ' aria-current="page"' : ""}>News</a></li></ul></nav></header>`;
    return renderMaster(template("master"), { title, baseHref: prefix }, content, navigation);
  };
  for (const page of pages) {
    const file = `${href(page.route)}index.html`;
    const breadcrumbs = page.ancestors.length
      ? `<nav aria-label="Breadcrumb"><ol class="cluster">${page.ancestors.map((ancestor, index) =>
        `<li><a href="${page.ancestors.slice(0, index + 1).map((item) => item.slug).join("/")}/">${escapeHtml(ancestor.menuTitle)}</a></li>`).join("")
        }<li>${escapeHtml(page.record.menuTitle)}</li></ol></nav>` : "";
    files.push({ path: file, html: document(page.record.title,
      breadcrumbs + pageContent(page), file, page.record.id) });
  }
  const news = sortedNews(project.news);
  for (const item of news) {
    const file = `news/${item.date}/${item.slug}/index.html`;
    files.push({ path: file, html: document(item.title,
      render(template("news"), item, body(item, catalog, project.media)), file) });
  }
  const articles = news.slice(0, 3).map((item) => `<article class="stack news-summary">
    <header class="stack"><h2><a href="news/${item.date}/${item.slug}/">${escapeHtml(item.title)}</a></h2>
    <time datetime="${item.date}">${item.date}</time></header>${coverMarkup(item, project.media)}${rich(item.shortContent)}</article>`).join("\n");
  const listing = render(template("news-index"), {}, articles || "<p>No news yet.</p>");
  files.push({ path: "news/index.html", html: document("News", listing, "news/index.html") });
  const homepage = homeEntry ? pageContent(homeEntry) : listing;
  files.push({ path: "index.html", html: document(home?.title ?? "News", homepage, "index.html", home?.id) });
  return files;
}

export function referencedMedia(project, window) {
  const paths = new Set();
  const visit = (record) => {
    if (Array.isArray(record)) { record.forEach(visit); return; }
    if (!record || typeof record !== "object") return;
    const cover = coverMedia(record, project.media);
    if (cover) paths.add(`media/${cover.filename}`);
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
