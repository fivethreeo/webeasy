import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { projectChanges } from "../src/content-changes.js";

const seed = JSON.parse(await readFile(new URL("../src/content.json", import.meta.url), "utf8"));
const fresh = () => structuredClone(seed);

test("native placeholder fields and serialization order do not appear as changes", () => {
  const current = fresh();
  current.pages[0].templateId ??= "page";
  current.news[0].children = [];
  current.news[0].content = "<p></p>";
  current.news[0].menuTitle = "New page";
  assert.deepEqual(projectChanges(seed, current), []);
});

test("field comparisons retain before and after values, including incomplete drafts", () => {
  const current = fresh();
  current.pages[0].title = "";
  current.pages[0].content = "<p>Changed <strong>content</strong>.</p>";
  const changes = projectChanges(seed, current);
  assert.equal(changes.length, 1);
  assert.equal(changes[0].status, "Changed");
  assert.deepEqual(changes[0].fields, [
    { name: "Title", before: seed.pages[0].title, after: "" },
    { name: "Content", before: seed.pages[0].content, after: current.pages[0].content },
  ]);
});

test("added news and removed owned sections and asides are reported without changing input", () => {
  const current = fresh();
  const removed = current.pages[0].sections.pop();
  current.news.push({ id: "added-news", title: "New news", slug: "new-news", date: "2026-11-25", shortContent: "<p>New.</p>" });
  const changes = projectChanges(seed, current);
  assert.equal(changes.find((item) => item.id === "added-news").status, "Added");
  assert.equal(changes.find((item) => item.id === removed.id).status, "Removed");
  assert.equal(changes.find((item) => item.id === removed.asides[0].id).status, "Removed");
  assert.deepEqual(seed, fresh());
});

test("page and section reordering is visible but news sorting and index shifts are not changes", () => {
  const current = fresh();
  current.news.reverse();
  assert.deepEqual(projectChanges(seed, current), []);
  const second = { ...structuredClone(current.pages[0]), id: "second", children: [], sections: [], asides: [] };
  const before = { ...fresh(), homePageId: seed.pages[0].id, pages: [...fresh().pages, second] };
  const after = structuredClone(before);
  after.pages.reverse();
  assert.deepEqual(projectChanges(before, after).map((item) => item.title), ["Page order"]);
  after.pages = [after.pages[0]];
  assert.ok(projectChanges(before, after).every((item) => !item.title.endsWith(" order")));
  const sections = fresh();
  sections.pages[0].sections.push({ ...structuredClone(sections.pages[0].sections[0]), id: "second-section", asides: [] });
  const reordered = structuredClone(sections);
  reordered.pages[0].sections.reverse();
  assert.deepEqual(projectChanges(sections, reordered).map((item) => item.title),
    [`Page: ${sections.pages[0].title} / Section order`]);
});

test("homepage selection and imported media changes are included", () => {
  const current = fresh();
  current.homePageId = current.pages[0].children[0].id;
  current.media = [{ id: "photo", filename: "coast.jpg", mimeType: "image/jpeg", originalName: "Camera.jpg", size: 12 }];
  const changes = projectChanges(seed, current);
  assert.equal(changes[0].title, "Homepage");
  assert.equal(changes[0].fields[0].after, current.pages[0].children[0].title);
  assert.equal(changes.find((item) => item.id === "photo").status, "Added");
});

test("saving does not clear unpublished changes and a published snapshot excludes later edits", () => {
  const published = fresh();
  const current = fresh();
  current.news[0].shortContent = "<p>Updated news.</p>";
  const saved = structuredClone(current);
  assert.deepEqual(projectChanges(saved, current), []);
  assert.equal(projectChanges(published, current).length, 1);
  const uploaded = structuredClone(current);
  current.news[0].title = "Edited during upload";
  assert.deepEqual(projectChanges(uploaded, current)[0].fields.map((field) => field.name), ["Title"]);
});
