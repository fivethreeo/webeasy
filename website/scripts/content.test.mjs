import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { loadCatalog } from "./catalog.mjs";
import { loadProject, sanitizeRichText, contentWindow } from "./content.mjs";
import { normalizeProject, pageRoutes, renderSite, referencedMedia } from "../src/content-model.js";

const source = fileURLToPath(new URL("../src", import.meta.url));
const input = JSON.parse(await readFile(new URL("../src/content.json", import.meta.url), "utf8"));
const catalog = await loadCatalog(source);
const normalize = (project) => normalizeProject(project, sanitizeRichText);
const dom = (html) => new JSDOM(html).window.document;

test("rich text keeps supported formatting and local figures, but removes executable and remote markup", () => {
  const html = sanitizeRichText(`<h1>Heading</h1><p><b>B</b><strong>S</strong><i>I</i><em>E</em><u>U</u><s>S</s><del>D</del></p>
    <ul><li>One</li></ul><ol><li>Two</li></ol><blockquote>Quote</blockquote>
    <img src="media/photo.jpg" alt="A photo" onerror="alert(1)" style="color:red">
    <figure><video autoplay src="https://example.invalid/clip.mp4" poster="assets/coast.svg"><source src="media/clip.mp4" type="video/mp4"></video><figcaption>Caption</figcaption></figure>
    <script>alert(1)</script><iframe src="https://example.invalid"></iframe><svg onload="alert(1)"></svg>`);
  const document = dom(html);
  assert.equal(document.querySelector("h1"), null);
  assert.equal(document.querySelector("h2").textContent, "Heading");
  for (const tag of ["b", "strong", "i", "em", "u", "s", "del", "ul", "ol", "blockquote"]) assert.ok(document.querySelector(tag));
  assert.equal(document.querySelector("img").parentElement.tagName, "FIGURE");
  assert.equal(document.querySelector("img").getAttribute("src"), "media/photo.jpg");
  assert.equal(document.querySelector("source").getAttribute("src"), "media/clip.mp4");
  assert.equal(document.querySelector("video").getAttribute("poster"), "assets/coast.svg");
  assert.ok(document.querySelector("video").hasAttribute("controls"));
  assert.ok(document.querySelector("video").hasAttribute("playsinline"));
  assert.equal(document.querySelector("video").hasAttribute("autoplay"), false);
  assert.equal(document.querySelector("video").hasAttribute("src"), false);
  assert.equal(document.querySelector("[onerror],[style],script,iframe,svg"), null);
  assert.equal(sanitizeRichText(html), html);
});

test("media URLs cannot reference external, temporary device, encoded, or parent paths", () => {
  for (const path of ["https://example.invalid/p.jpg", "//example.invalid/p.jpg", "javascript:alert(1)",
    "data:image/png;base64,AAAA", "file:///private/photo.jpg", "content://photos/1",
    "media/../secret.jpg", "media/%2e%2e/secret.jpg", "/media/photo.jpg"]) {
    assert.equal(dom(sanitizeRichText(`<img src="${path}">`)).querySelector("img"), null, path);
  }
});

test("figure text becomes one figcaption while preserving media, formatting, and surrounding paragraphs", () => {
  const html = sanitizeRichText('<p>Before</p><figure><img src="assets/coast.svg" alt="Coast">Our <strong>coast</strong><p>A note.</p><figcaption>Existing caption.</figcaption></figure><p>After</p>');
  const document = dom(html);
  const figure = document.querySelector("figure");
  assert.equal(figure.querySelectorAll(":scope > figcaption").length, 1);
  assert.equal(figure.querySelector(":scope > img").getAttribute("src"), "assets/coast.svg");
  assert.equal(figure.querySelector("figcaption strong").textContent, "coast");
  assert.equal(figure.querySelector("figcaption").textContent, "Our coastA note.Existing caption.");
  const outsideCaption = figure.cloneNode(true);
  outsideCaption.querySelector("figcaption").remove();
  assert.equal(outsideCaption.textContent.trim(), "");
  assert.deepEqual([...document.body.children].map((node) => node.tagName), ["P", "FIGURE", "P"]);
  const withoutCaption = dom(sanitizeRichText('<figure><video src="media/clip.mp4"></video></figure>'));
  assert.equal(withoutCaption.querySelector("figcaption"), null);
  assert.equal(sanitizeRichText(html), html);
});

test("links retain safe destinations and text but cannot execute scripts or open device files", () => {
  for (const href of ["https://example.com/path?q=1", "http://example.com/", "mailto:hello@example.com",
    "tel:+4712345678", "/", "index.html", "about/team/", "#section"]) {
    const link = dom(sanitizeRichText(`<p><a href="${href}" title="More" onclick="alert(1)" target="_blank">Read <strong>more</strong></a></p>`)).querySelector("a");
    assert.equal(link.getAttribute("href"), href);
    assert.equal(link.textContent, "Read more");
    assert.equal(link.getAttribute("title"), "More");
    assert.equal(link.hasAttribute("onclick"), false);
    assert.equal(link.hasAttribute("target"), false);
  }
  for (const href of ["javascript:alert(1)", "java&#10;script:alert(1)", "data:text/html,hello",
    "file:///private/secret", "content://photos/1", "//example.com/", "\\\\example.com",
    "../private", "about/%2e%2e/private"]) {
    assert.equal(dom(sanitizeRichText(`<a href="${href}">Keep text</a>`)).querySelector("a").hasAttribute("href"), false, href);
  }
});

test("deep subpages, owned sections/asides, and every dated news route are generated", async () => {
  const project = await loadProject(source);
  const child = structuredClone(project.pages[0].children[0]);
  child.id = "history"; child.slug = "history"; child.children = [];
  project.pages[0].children[0].children.push(child);
  const normalized = normalize(project);
  assert.deepEqual(pageRoutes(normalized.pages).map((item) => item.route), ["/about/", "/about/team/", "/about/team/history/"]);
  const files = renderSite(normalized, catalog);
  const deep = files.find((file) => file.path === "about/team/history/index.html").html;
  assert.ok(deep.includes('<base href="../../../">'));
  assert.equal(dom(deep).querySelectorAll('nav[aria-label="Breadcrumb"] a').length, 2);
  const page = dom(files.find((file) => file.path === "about/index.html").html);
  assert.equal(page.querySelectorAll(".content-blocks section").length, 1);
  assert.equal(page.querySelectorAll(".content-blocks aside").length, 2);
  const news = dom(files.find((file) => file.path === "news/2026-11-24/simpler-publishing/index.html").html);
  assert.equal(news.querySelectorAll("aside").length, 2);
  assert.ok(files.some((file) => file.path === "news/2026-11-21/first-story/index.html"));
  assert.ok(referencedMedia(normalized, contentWindow).includes("assets/coast.svg"));
});

test("news index contains only the three newest summaries as articles", () => {
  const files = renderSite(normalize(input), catalog);
  const index = dom(files.find((file) => file.path === "news/index.html").html);
  assert.deepEqual([...index.querySelectorAll("article time")].map((time) => time.dateTime),
    ["2026-11-24", "2026-11-23", "2026-11-22"]);
  assert.equal(index.querySelectorAll("article").length, 3);
  assert.ok(!index.body.textContent.includes("The first story"));
  for (const count of [0, 1, 2]) {
    const project = { version: 1, pages: [], news: input.news.slice(0, count), media: [] };
    const listing = renderSite(normalize(project), catalog).find((file) => file.path === "news/index.html");
    assert.equal(dom(listing.html).querySelectorAll("article").length, count);
  }
});

test("page layouts render sections on home and named routes while news remains an article", () => {
  const project = normalize(input);
  for (const layout of catalog.templates.filter((entry) => entry.pageLayout)) {
    project.pages[0].templateId = layout.id;
    const files = renderSite(project, catalog);
    for (const path of ["index.html", "about/index.html"]) {
      const document = dom(files.find((file) => file.path === path).html);
      assert.equal(document.querySelector(".content-blocks").tagName, "SECTION", layout.id);
      assert.equal(document.querySelector(".content-blocks h1").textContent, project.pages[0].title);
      assert.equal(document.querySelector("article"), null, layout.id);
    }
    const news = dom(files.find((file) => file.path === "news/2026-11-24/simpler-publishing/index.html").html);
    assert.equal(news.querySelector(".content-blocks").tagName, "ARTICLE");
    assert.equal(news.querySelector("article time").dateTime, "2026-11-24");
  }
});

test("empty optional relations normalize and unsafe or duplicate routes/IDs fail", () => {
  const fresh = () => structuredClone(input);
  let project = fresh();
  delete project.pages[0].sections; delete project.pages[0].asides; delete project.pages[0].children;
  assert.deepEqual(normalize(project).pages[0].children, []);
  for (const change of [
    (project) => { project.pages[0].date = "2026-02-31"; },
    (project) => { project.pages[0].slug = "../private"; },
    (project) => { project.pages[0].slug = "news"; },
    (project) => { project.news.push(structuredClone(project.news[0])); },
    (project) => { project.pages[0].sections[0].asides[0].id = project.pages[0].id; },
    (project) => { project.pages.push({ ...structuredClone(project.pages[0]), id: "duplicate" }); },
  ]) {
    project = fresh(); change(project); assert.throws(() => normalize(project));
  }
});

test("export references come only from real media elements, not text or unused metadata", () => {
  const project = normalize(input);
  project.pages[0].title = 'src="media/private.jpg"';
  project.pages[0].content = sanitizeRichText('<p>Type src="media/private.jpg" here.</p><video src="media/clip.mp4" poster="media/poster.jpg"></video>');
  assert.deepEqual(referencedMedia(project, contentWindow).sort(),
    ["assets/coast.svg", "media/clip.mp4", "media/poster.jpg"].sort());
});

test("page layouts apply independently to homepage and subpages and unavailable layouts fail", () => {
  const project = normalize(input);
  assert.equal(project.pages[0].templateId, "page");
  project.pages[0].templateId = "landing-page";
  project.pages[0].children[0].templateId = "reading-page";
  const files = renderSite(project, catalog);
  for (const path of ["index.html", "about/index.html"]) {
    assert.ok(dom(files.find((file) => file.path === path).html).querySelector(".landing-layout"));
  }
  assert.ok(dom(files.find((file) => file.path === "about/team/index.html").html).querySelector(".reading-layout"));
  project.pages[0].templateId = "news";
  assert.throws(() => renderSite(project, catalog), /Unavailable page layout/);
  project.pages[0].templateId = "../page";
  assert.throws(() => normalize(project), /Invalid page layout/);
});

test("homepage selection supports subpages, preserves their routes and validates references", () => {
  const project = normalize(input);
  assert.equal(project.homePageId, project.pages[0].id);
  const child = project.pages[0].children[0];
  project.homePageId = child.id;
  child.templateId = "reading-page";
  const files = renderSite(normalize(project), catalog);
  const home = dom(files.find((file) => file.path === "index.html").html);
  assert.equal(home.title, child.title);
  assert.ok(home.querySelector(".reading-layout"));
  assert.ok(files.some((file) => file.path === "about/team/index.html"));
  for (const id of ["missing", project.news[0].id, project.pages[0].sections[0].id, 42, ""]) {
    assert.throws(() => normalize({ ...project, homePageId: id }), /existing page/);
  }
  assert.equal(normalize({ version: 1, pages: [], news: [], media: [] }).homePageId, null);
});

test("homepage is hidden from menus but retains its named route and home-linked logo", () => {
  const project = normalize(input);
  const files = renderSite(project, catalog);
  for (const file of files) {
    const document = dom(file.html);
    assert.equal(document.querySelector('.site-menu a[href="about/"]'), null);
    assert.equal(document.querySelector(".site-header > .site-logo").getAttribute("href"), "./");
    const hosted = new JSDOM(file.html, { url: `http://example.test/host/${file.path}` }).window.document;
    assert.equal(hosted.querySelector(".site-logo").href, "http://example.test/host/");
    assert.equal(document.querySelector(".site-logo img").getAttribute("src"), "assets/logo.svg");
  }
  assert.ok(files.some((file) => file.path === "about/index.html"));
  assert.equal(dom(files.find((file) => file.path === "index.html").html).querySelector(".site-logo").getAttribute("aria-current"), "page");
  project.homePageId = project.pages[0].children[0].id;
  for (const file of renderSite(project, catalog)) {
    assert.equal(dom(file.html).querySelector('.subpage-navigation a[href="about/team/"]'), null);
  }
});

test("an explicit public URL prefix controls home links without changing content or generated file paths", () => {
  const project = normalize(input);
  const files = renderSite(project, catalog, { publicPathPrefix: "/host/site/" });
  assert.deepEqual(files.map((file) => file.path), renderSite(project, catalog).map((file) => file.path));
  for (const file of files) {
    const document = new JSDOM(file.html, { url: `http://example.test/host/site/${file.path}` }).window.document;
    assert.equal(document.querySelector(".site-logo").getAttribute("href"), "/host/site/");
    assert.equal(document.querySelector(".site-logo").href, "http://example.test/host/site/");
    assert.equal(document.querySelector(".site-logo img").src, "http://example.test/host/site/assets/logo.svg");
    assert.equal(document.querySelector('.site-menu a[href="news/"]').href, "http://example.test/host/site/news/");
  }
  assert.equal(project.publicPathPrefix, undefined);
  assert.throws(() => renderSite(project, catalog, { publicPathPrefix: "/host/../private/" }));
});

test("Rsbuild example home links stay in the example site rather than returning to the catalog", () => {
  for (const file of renderSite(normalize(input), catalog, { homeHref: "home.html" })) {
    const document = new JSDOM(file.html, { url: `http://example.test/${file.path}` }).window.document;
    assert.equal(document.querySelector(".site-logo").href, "http://example.test/home.html");
  }
});

test("main menu lists top-level pages and marks their active ancestor on nested routes and homepage", () => {
  const project = normalize(input);
  const root = project.pages[0];
  const child = root.children[0];
  child.children.push({ ...structuredClone(child), id: "deep-menu", slug: "deep", children: [] });
  project.pages.push({
    id: "other-menu", title: "Other", menuTitle: "Other", slug: "other", date: "2026-11-01",
    content: "<p>Other page</p>", templateId: "page", children: [], sections: [], asides: [],
  });

  project.homePageId = child.children[0].id;
  const files = renderSite(normalize(project), catalog);
  for (const path of ["index.html", "about/index.html", "about/team/index.html", "about/team/deep/index.html", "other/index.html"]) {
    const menu = dom(files.find((file) => file.path === path).html).querySelector('nav[aria-label="Main menu"]');
    assert.deepEqual([...menu.querySelectorAll("a")].map((link) => link.getAttribute("href")), ["about/", "other/", "news/"]);
    assert.equal(menu.querySelectorAll("a.active").length, 1);
    assert.equal(menu.querySelector("a.active").getAttribute("href"), path.startsWith("other/") ? "other/" : "about/");
    assert.equal(menu.querySelectorAll('a[aria-current="page"]').length, ["about/index.html", "other/index.html"].includes(path) ? 1 : 0);
  }
  assert.equal(dom(files.find((file) => file.path === "news/index.html").html).querySelector(".site-menu a.active").getAttribute("href"), "news/");
});

test("subpage navigation is an aside shared by its parent and descendants, not by standalone pages", () => {
  const project = normalize(input);
  const root = project.pages[0];
  const child = root.children[0];
  child.children.push({ ...structuredClone(child), id: "deep-aside", slug: "deep", children: [] });
  project.pages.push({
    id: "standalone", title: "Standalone", menuTitle: "Standalone", slug: "standalone", date: "2026-11-01",
    content: "<p>Standalone</p>", children: [], sections: [], asides: [],
  });

  project.homePageId = child.id;
  const files = renderSite(normalize(project), catalog);
  for (const path of ["index.html", "about/index.html", "about/team/index.html", "about/team/deep/index.html"]) {
    const page = dom(files.find((file) => file.path === path).html);
    const nav = page.querySelector('aside nav[aria-label="Subpages"]');
    assert.ok(nav, path);
    assert.deepEqual([...nav.querySelectorAll("a")].map((link) => link.getAttribute("href")), ["about/team/deep/"]);
    const current = nav.querySelector('[aria-current="page"]');
    assert.equal(current?.getAttribute("href") ?? null,
      path.includes("/deep/") ? "about/team/deep/" : null);
    if (current) assert.ok(current.classList.contains("active"));
  }
  for (const path of ["standalone/index.html", "news/index.html"]) {
    assert.equal(dom(files.find((file) => file.path === path).html).querySelector(".subpage-navigation"), null);
  }
});

test("News stays active on listings and articles, but aria-current identifies only the listing", () => {
  const project = normalize(input);
  const files = renderSite(project, catalog);
  for (const file of files.filter((file) => file.path.startsWith("news/"))) {
    const menu = dom(file.html).querySelector(".site-menu");
    assert.equal(menu.querySelectorAll("a.active").length, 1);
    const link = menu.querySelector('a[href="news/"]');
    assert.ok(link.classList.contains("active"), file.path);
    assert.equal(link.getAttribute("aria-current"), file.path === "news/index.html" ? "page" : null);
  }
  const page = dom(files.find((file) => file.path === "about/index.html").html);
  assert.equal(page.querySelector('.site-menu a[href="news/"]').classList.contains("active"), false);
  const fallback = renderSite(normalize({ ...input, pages: [] }), catalog).find((file) => file.path === "index.html");
  assert.equal(dom(fallback.html).querySelector('.site-menu a.active[aria-current="page"]').getAttribute("href"), "news/");
});
