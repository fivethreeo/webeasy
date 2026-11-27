import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { loadCatalog } from "./catalog.mjs";
import { loadProject, sanitizeRichText, sanitizeShortText, contentWindow } from "./content.mjs";
import { normalizeProject, pageRoutes, renderSite, referencedMedia } from "../src/content-model.js";
import { uniqueHeadingId } from "../src/heading-id.js";

const source = fileURLToPath(new URL("../src", import.meta.url));
const input = JSON.parse(await readFile(new URL("../src/content.json", import.meta.url), "utf8"));
const catalog = await loadCatalog(source);
const normalize = (project) => normalizeProject(project, sanitizeRichText, sanitizeShortText);
const dom = (html) => new JSDOM(html).window.document;

test("short news uses only paragraphs and inline formatting while long news retains rich content", () => {
  const short = sanitizeShortText('<h2>Intro <strong>bold</strong></h2><ul><li>One</li><li><em>Two</em></li></ul><blockquote>Quote</blockquote><figure><img src="media/photo.jpg"><figcaption>Caption</figcaption></figure><div>New paragraph <a href="/about/">link</a></div>');
  const document = dom(short);
  assert.deepEqual([...document.body.children].map((node) => node.tagName), ["P", "P", "P", "P", "P", "P"]);
  assert.ok(document.querySelector("strong"));
  assert.ok(document.querySelector("em"));
  assert.equal(document.querySelector("a").getAttribute("href"), "/about/");
  assert.equal(document.querySelector("h2,ul,li,blockquote,figure,img,video"), null);
  assert.equal(sanitizeShortText(short), short);
  const project = structuredClone(input);
  project.news[0].shortContent = "<p>Summary only</p>";
  project.news[0].content = "<h2>Full story</h2><p>Long body</p>";
  const files = renderSite(normalize(project), catalog);
  const full = files.find((file) => file.path === `news/${project.news[0].date}/${project.news[0].slug}/index.html`).html;
  assert.ok(full.includes("Full story"));
  assert.ok(!full.includes("Summary only"));
  const listing = files.find((file) => file.path === "news/index.html").html;
  assert.ok(listing.includes("Summary only"));
  assert.ok(!listing.includes("Long body"));
});

test("legacy news keeps its full short-field markup as long content and covers appear in both news views", () => {
  const project = structuredClone(input);
  project.news[0].shortContent = '<h2>Legacy heading</h2><figure><img src="media/legacy.jpg" alt="Legacy"></figure>';
  project.media = [{ id: "legacy-media", filename: "legacy.jpg", mimeType: "image/jpeg", originalName: "legacy.jpg", size: 12 }];
  project.news[0].coverMediaId = "legacy-media";
  project.news[0].coverAlt = '"<cover>';
  const normalized = normalize(project);
  assert.ok(normalized.news[0].content.includes("<figure"));
  assert.equal(dom(normalized.news[0].shortContent).querySelector("img"), null);
  const files = renderSite(normalized, catalog);
  const paths = ["news/index.html", `news/${project.news[0].date}/${project.news[0].slug}/index.html`];
  for (const path of paths) {
    const image = dom(files.find((file) => file.path === path).html).querySelector(".cover-image img");
    assert.equal(image.getAttribute("src"), "media/legacy.jpg");
    assert.equal(image.getAttribute("alt"), '"<cover>');
  }
  assert.ok(referencedMedia(normalized, contentWindow).includes("media/legacy.jpg"));
});

test("section headings use Prosango slugs, collision tracking and escaped optional subheadings", () => {
  const used = new Set();
  assert.equal(uniqueHeadingId("Héllo & World!", used), "hello-world");
  assert.equal(uniqueHeadingId("Hello world", used), "hello-world-1");
  assert.equal(uniqueHeadingId("Hello world", used), "hello-world-2");
  assert.equal(uniqueHeadingId("Hello world-1", used), "hello-world-1-1");
  assert.equal(uniqueHeadingId("!!!", used), "label");
  assert.equal(uniqueHeadingId("!!!", used), "label-1");
  assert.equal(uniqueHeadingId("a".repeat(40), used).length, 25);
  assert.equal(uniqueHeadingId("a".repeat(40), used), `${"a".repeat(23)}-1`);
  const project = structuredClone(input);
  project.pages[0].sections = [
    { id: "heading-test-a", heading: "Héllo World", subheading: "<img src=x onerror=alert(1)>", content: "<p>Body</p>" },
    { id: "heading-test-b", heading: "Hello World", content: "<p>Body</p>" },
    { id: "heading-test-c", heading: "Hello World", subheading: " ", content: "<p>Body</p>" },
  ];
  const page = dom(renderSite(normalize(project), catalog).find((file) => file.path === "about/index.html").html);
  const sections = [...page.querySelectorAll("section[aria-labelledby]")];
  assert.deepEqual(sections.map((section) => section.getAttribute("aria-labelledby")), ["hello-world", "hello-world-1", "hello-world-2"]);
  for (const section of sections) {
    assert.equal(section.querySelector(":scope > header > h2").id, section.getAttribute("aria-labelledby"));
  }
  assert.equal(sections[0].querySelector(":scope > header > p").textContent, "<img src=x onerror=alert(1)>");
  assert.equal(sections[0].querySelector("header img"), null);
  assert.equal(sections[1].querySelector("header p"), null);
  assert.equal(sections[2].querySelector("header p"), null);
});

test("rich text keeps supported formatting and local figures, but removes executable and remote markup", () => {
  const html = sanitizeRichText(`<h1>Heading</h1><p><b>B</b><strong>S</strong><i>I</i><em>E</em><u>U</u><s>S</s><del>D</del></p>
    <ul><li>One</li></ul><ol><li>Two</li></ol><blockquote>Quote</blockquote>
    <img src="media/photo.jpg" alt="A photo" onerror="alert(1)" style="color:red">
    <figure><video autoplay src="https://example.invalid/clip.mp4" poster="assets/coast.svg"><source src="media/clip.mp4" type="video/mp4"></video><figcaption>Caption</figcaption></figure>
    <script>alert(1)</script><iframe src="https://example.invalid"></iframe><svg onload="alert(1)"></svg>`);
  const document = dom(html);
  assert.equal(document.querySelector("h1"), null);
  assert.equal(document.querySelector("h2").textContent, "Heading");
  for (const tag of ["strong", "i", "em", "u", "s", "del", "ul", "ol", "blockquote"]) assert.ok(document.querySelector(tag));
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

test("legacy bold markup becomes semantic strong without losing links or text", () => {
  const input = '<p><b>Bold <a href="about/">link</a></b> and <strong>strong</strong>.</p>';
  const expected = '<p><strong>Bold <a href="about/">link</a></strong> and <strong>strong</strong>.</p>';
  for (const sanitize of [sanitizeRichText, sanitizeShortText]) {
    assert.equal(sanitize(input), expected);
    assert.equal(sanitize(expected), expected);
  }
});

test("rich text wraps loose inline runs in paragraphs without changing surrounding blocks or captions", () => {
  assert.equal(sanitizeRichText("It&nbsp;<strong>works</strong>!<p></p>"),
    "<p>It&nbsp;<strong>works</strong>!</p><p></p>");
  const html = sanitizeRichText('<h2>Heading</h2>\nFirst <strong>bold</strong>!<p>Existing</p>' +
    'Next <a href="about/">link</a><figure><img src="media/photo.jpg"><figcaption>Caption</figcaption></figure>Last<br>line');
  const document = dom(html);
  assert.deepEqual([...document.body.children].map((node) => node.tagName), ["H2", "P", "P", "P", "FIGURE", "P"]);
  assert.deepEqual([...document.querySelectorAll("body > p")].map((node) => node.textContent),
    ["\nFirst bold!", "Existing", "Next link", "Lastline"]);
  assert.equal(document.querySelector("p a").getAttribute("href"), "about/");
  assert.equal(document.querySelector("figcaption").innerHTML, "Caption");
  assert.equal(document.querySelector("body > p:last-child").innerHTML, "Last<br>line");
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
  assert.equal(page.querySelectorAll(".content-main section").length, 1);
  assert.equal(page.querySelectorAll(".content-main aside").length, 2);
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

test("simple pages render directly in main, layout containers are nonsemantic, and news remains an article", () => {
  const project = normalize(input);
  project.pages[0].children = [];
  project.pages[0].sections = [];
  project.pages[0].asides = [];
  for (const layout of catalog.templates.filter((entry) => entry.pageLayout)) {
    project.pages[0].templateId = layout.id;
    const files = renderSite(project, catalog);
    for (const path of ["index.html", "about/index.html"]) {
      const document = dom(files.find((file) => file.path === path).html);
      assert.equal(document.querySelector("main h1").textContent, project.pages[0].title);
      assert.equal(document.querySelector("main > section"), null, layout.id);
      assert.equal(document.querySelector("article"), null, layout.id);
      if (layout.id === "page") {
        assert.ok(document.querySelector("main > header:not(.site-header) h1"));
        assert.ok(document.querySelector("main > .rich-content"));
        assert.equal(document.querySelector(".content-blocks"), null);
      } else {
        assert.equal(document.querySelector(".content-blocks").tagName, "DIV", layout.id);
      }
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

test("rich homepage links resolve to the publishing root without changing stored content", () => {
  const project = normalize(input);
  const markup = sanitizeRichText(`<p><a href="/" title="literal
 href='/'">Home</a> <a href="index.html#details">Legacy home</a>
 <a href="/?search=one&amp;two=2">Search</a>
 <a href="about/">Page</a> <a href="/other/">Other root</a>
 <a href="https://example.org/index.html">External</a> Literal href='/'</p>`);
  for (const entry of pageRoutes(project.pages)) {
    entry.record.content = markup;
    entry.record.asides = [{ heading: "", content: markup }];
    entry.record.sections = [{ heading: "Section", content: markup, asides: [{ heading: "", content: markup }] }];
  }
  for (const item of project.news) { item.content = markup; item.shortContent = markup; }
  const original = JSON.stringify(project);
  for (const [options, homeHref] of [
    [{ publicPathPrefix: "/host/site/" }, "/host/site/"],
    [{ publicPathPrefix: "/" }, "/"],
    [{}, "./"],
    [{ homeHref: "home.html" }, "home.html"],
  ]) {
    for (const file of renderSite(project, catalog, options)) {
      const document = new JSDOM(file.html, { url: `http://example.test/host/site/${file.path}` }).window.document;
      const blocks = [...document.querySelectorAll(".rich-content")].filter((block) => block.querySelector("a"));
      assert.ok(blocks.length);
      for (const block of blocks) {
        const links = [...block.querySelectorAll("a")];
        assert.equal(links[0].getAttribute("href"), homeHref);
        assert.equal(links[0].getAttribute("title"), "literal\n href='/'");
        assert.equal(links[1].getAttribute("href"), `${homeHref}#details`);
        assert.equal(links[2].getAttribute("href"), `${homeHref}?search=one&two=2`);
        assert.equal(links[3].getAttribute("href"), "about/");
        assert.equal(links[4].getAttribute("href"), "/other/");
        assert.equal(links[5].getAttribute("href"), "https://example.org/index.html");
        assert.match(block.textContent, /Literal href='\/'/);
      }
    }
  }
  assert.equal(JSON.stringify(project), original);
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
