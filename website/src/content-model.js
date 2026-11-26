import { escapeHtml, catalogPartials } from "./render.js";
import { renderRequest } from "./c-renderer.js";
import { uniqueHeadingId } from "./heading-id.js";
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
function rich(html, homeHref) {
  // Sanitized rich text has quoted attributes; consume whole attributes, including titles.
  const content = homeHref ? html.replace(/<a\b(?:[^"'<>]|"[^"]*"|'[^']*')*>/gi, (tag) =>
    tag.replace(/(\s+)([\w:-]+)(\s*=\s*)(["'])([\s\S]*?)\4/g, (attribute, space, name, equals, quote, value) => {
      if (name.toLowerCase() !== "href") return attribute;
      const home = /^(?:\/|(?:\.\/)?index\.html)([?#].*)?$/.exec(value);
      return home ? `${space}${name}${equals}${quote}${escapeHtml(homeHref)}${home[1] ?? ""}${quote}` : attribute;
    })) : html;
  return content;
}

function coverMedia(record, media = []) {
  if (!record.coverMediaId) return null;
  const image = media.find((item) => item.id === record.coverMediaId);
  if (!image || !image.mimeType.startsWith("image/") || !/\.(jpg|jpeg|png|gif|webp)$/.test(image.filename)) {
    throw new Error("Choose an existing managed image for the cover.");
  }
  return image;
}

function subpageItems(entry, homeId) {
  const top = entry.ancestors[0] ?? entry.record;
  const active = new Set([...entry.ancestors.map((page) => page.id), entry.record.id]);
  const items = (pages, prefix) => pages.flatMap((page) => {
    const route = `${prefix}${page.slug}/`;
    if (page.id === homeId) return items(page.children, route);
    const children = items(page.children, route);
    return [{ id: page.id, title: page.title, menuTitle: page.menuTitle, href: route,
      active: active.has(page.id), current: page.id === entry.record.id,
      children, hasChildren: children.length > 0 }];
  });
  return items(top.children, `${top.slug}/`);
}

const composed = (template, values) => ({ $render: template, values });

export function prepareSite(project, catalog, options = {}) {
  const { publicPathPrefix } = normalizePublishingSettings({ publicPathPrefix: options.publicPathPrefix });
  const homeHref = options.homeHref ?? (publicPathPrefix || "./");
  if (homeHref !== "./" && !safeLinkHref(homeHref)) throw new Error("Invalid website home URL.");
  const partials = catalogPartials(catalog);
  const pageTemplate = (record) => {
    const id = record.templateId ?? "page";
    if (id !== "page" && !catalog.templates.some((entry) => entry.id === id && entry.pageLayout === true)) {
      throw new Error(`Unavailable page layout: ${id}`);
    }
    if (!partials[id]) throw new Error(`Template is missing: ${id}`);
    return id;
  };
  const documents = [];
  const pages = pageRoutes(project.pages);
  const homeEntry = pages.find((entry) => entry.record.id === project.homePageId) ?? pages[0];
  const home = homeEntry?.record;
  const routes = new Map(pages.map((entry) => [entry.record.id, href(entry.route)]));
  const pageLink = (page) => ({ id: page.id, title: page.title, menuTitle: page.menuTitle,
    slug: page.slug, date: page.date, href: routes.get(page.id) });
  const asideData = (asides = []) => asides.map((aside) => {
    const html = rich(aside.content, homeHref);
    return { ...aside, html, content: composed("aside-content", {
      heading: aside.heading ?? "", content: composed("rich-content", { html }),
    }) };
  });
  const recordData = (record) => {
    const usedIds = new Set();
    const sections = (record.sections ?? []).map((section) => {
      const html = rich(section.content, homeHref);
      const asides = asideData(section.asides);
      const values = { html, asides, hasAsides: asides.length > 0, content: composed("rich-content", { html }) };
      const fields = { ...section, ...values, headingId: uniqueHeadingId(section.heading, usedIds),
        subheading: (section.subheading ?? "").trim() };
      return { ...fields, content: composed("section-content", {
        ...fields, content: composed("with-asides", values),
      }) };
    });
    const image = coverMedia(record, project.media);
    const html = rich(record.content ?? record.shortContent, homeHref);
    const asides = asideData(record.asides);
    const values = { ...record, children: (record.children ?? []).map(pageLink),
      html, sections, asides, hasAsides: asides.length > 0,
      cover: image ? { src: `media/${image.filename}`, alt: record.coverAlt ?? "" } : null,
      content: composed("main-content", { html, sections }) };
    return { ...values, content: composed("body", values) };
  };
  const pageContent = (entry, breadcrumbs = false) => {
    const subpages = subpageItems(entry, home?.id);
    return composed("page-content", {
      menuTitle: entry.record.menuTitle, hasSubpages: subpages.length > 0, subpages,
      hasBreadcrumbs: breadcrumbs && entry.ancestors.length > 0,
      breadcrumbs: entry.ancestors.map((ancestor, index) => ({ ...pageLink(ancestor),
        href: `${entry.ancestors.slice(0, index + 1).map((item) => item.slug).join("/")}/` })),
      content: composed(pageTemplate(entry.record), recordData(entry.record)),
    });
  };
  const document = (title, content, file, currentId = null, extra = {}) => {
    const prefix = "../".repeat(file.split("/").length - 1) || "./";
    const current = pages.find((entry) => entry.record.id === currentId);
    const activeId = current?.ancestors[0]?.id ?? currentId;
    const newsIndex = file === "news/index.html" || (file === "index.html" && !pages.length);
    const newsActive = newsIndex || file.startsWith("news/");
    const navigation = { homeHref, homeCurrent: currentId === home?.id && Boolean(home), newsActive, newsCurrent: newsIndex,
      menu: project.pages.filter((page) => page.id !== home?.id).map((page) => ({
        ...pageLink(page), active: page.id === activeId, current: page.id === currentId,
      })) };
    documents.push({ path: file, template: "master", values: { ...extra, ...navigation, title, baseHref: prefix,
      content, navigation: composed("site-header", navigation) } });
  };
  for (const page of pages) {
    const file = `${href(page.route)}index.html`;
    document(page.record.title, pageContent(page, true), file, page.record.id, { page: recordData(page.record) });
  }
  const news = sortedNews(project.news);
  for (const item of news) {
    const file = `news/${item.date}/${item.slug}/index.html`;
    const article = recordData(item);
    document(item.title, composed("news", article), file, null, { news: article });
  }
  const allItems = news.map((item) => {
    const image = coverMedia(item, project.media);
    return { id: item.id, title: item.title, date: item.date, slug: item.slug,
      html: rich(item.shortContent, homeHref), shortContent: item.shortContent,
      cover: image ? { src: `media/${image.filename}`, alt: item.coverAlt ?? "" } : null,
      href: `news/${item.date}/${item.slug}/` };
  });
  const listingValues = { items: allItems.slice(0, 3), allItems };
  const listing = composed("news-index", { ...listingValues, content: composed("news-list", listingValues) });
  document("News", listing, "news/index.html", null, listingValues);
  const homepage = homeEntry ? pageContent(homeEntry) : listing;
  document(home?.title ?? "News", homepage, "index.html", home?.id, home ? { page: recordData(home) } : listingValues);
  return { partials, documents, rawSlots: ["content", "navigation", "html"] };
}

export function renderSite(project, catalog, options = {}) {
  return renderRequest(prepareSite(project, catalog, options));
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
