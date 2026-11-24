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
  assert.equal(page.querySelectorAll("article section").length, 1);
  assert.equal(page.querySelectorAll("aside").length, 2);
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
