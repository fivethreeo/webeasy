import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

const project = JSON.parse(await readFile(new URL("../src/content.json", import.meta.url), "utf8"));
const photo = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", "base64");
const clip = await readFile(new URL("./fixtures/clip.mp4", import.meta.url));

async function openEditor(page) {
  const catalog = JSON.parse(await readFile(new URL("../dist/catalog.json", import.meta.url), "utf8"));
  await page.addInitScript(({ project, catalog, photoSize, videoSize }) => {
    window.__savedProject = project;
    window.WebeasyNative = {
      request(serialized) {
        const { id, method, payload } = JSON.parse(serialized);
        let result = null;
        if (method === "load") result = { project: window.__savedProject, catalog };
        if (method === "save") window.__savedProject = payload.project;
        if (method === "pickMedia") result = payload.kind === "image"
          ? { id: "imported-image", filename: "native-test.png", mimeType: "image/png", originalName: "Phone photo.png", size: photoSize }
          : { id: "imported-video", filename: "native-test.mp4", mimeType: "video/mp4", originalName: "Phone clip.mp4", size: videoSize };
        if (method === "export") window.__exported = payload;
        queueMicrotask(() => window.WebeasyBridge.receive({ id, result }));
      },
    };
  }, { project, catalog, photoSize: photo.length, videoSize: clip.length });
  await page.route("**/media/native-test.png", (route) => route.fulfill({ contentType: "image/png", body: photo }));
  await page.route("**/media/native-test.mp4", (route) => route.fulfill({ contentType: "video/mp4", body: clip }));
  await page.goto("/editor.html");
  await expect(page.getByRole("status")).toHaveText("Ready.");
}

test("edits after repeated saves, nested content, and subpages remain in the saved project", async ({ page }) => {
  await openEditor(page);
  const title = page.getByLabel("Title", { exact: true });
  await title.fill("First edit");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Saved locally to SQLite.");
  await title.fill("Second edit");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.pages[0].title)).toBe("Second edit");
  await page.getByRole("button", { name: "Add subpage", exact: true }).click();
  await page.getByLabel("Title", { exact: true }).fill("A phone subpage");
  await page.getByLabel("Menu title").fill("Phone");
  await page.getByLabel("Slug").fill("phone");
  await page.getByRole("button", { name: "Add section", exact: true }).click();
  await page.getByLabel("Section heading").fill("A new section");
  await page.getByRole("button", { name: "Add aside", exact: true }).last().click();
  await page.getByLabel("Heading (optional)").fill("Section note");
  await page.getByRole("textbox", { name: "Aside content", exact: true }).fill("Owned by this section");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.pages[0].children.at(-1).sections[0].asides[0].content)).toContain("Owned by this section");
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(page.locator("#preview").getByRole("heading", { name: "A phone subpage", exact: true })).toBeVisible();
  await expect(page.locator("#preview .content-asides aside")).toHaveText(/Section note.*Owned by this section/s);
});

test("phone image/video imports become figures with captions and exportable relative paths", async ({ page }) => {
  await openEditor(page);
  await page.getByRole("button", { name: "Phone image", exact: true }).first().click();
  await page.getByLabel("Alternative text (images)").fill("From my phone");
  await page.getByLabel("Caption (optional)").fill("A phone photograph");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(page.locator(".rich-editor").first().locator('figure img[alt="From my phone"]')).toHaveAttribute("src", "media/native-test.png");
  await expect.poll(() => page.locator('img[alt="From my phone"]').evaluate((image) => image.naturalWidth)).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Phone video", exact: true }).first().click();
  await page.getByLabel("Caption (optional)").fill("A phone clip");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  const video = page.locator(".rich-editor").first().locator("figure video");
  await expect(video.locator("source")).toHaveAttribute("src", "media/native-test.mp4");
  await expect(video).toHaveAttribute("controls", "");
  await video.scrollIntoViewIfNeeded();
  await video.evaluate(async (video) => { video.muted = true; await video.play(); });
  await expect.poll(() => video.evaluate((video) => video.readyState)).toBeGreaterThanOrEqual(2);
  await page.getByRole("button", { name: "Export website", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__exported?.files?.length)).toBeGreaterThan(0);
  const exported = await page.evaluate(() => window.__exported);
  const html = exported.files.find((file) => file.path === "about/index.html").html;
  expect(html).toContain('<figcaption>A phone photograph</figcaption>');
  expect(html).toContain('<figcaption>A phone clip</figcaption>');
  expect(html).toContain('src="media/native-test.mp4"');
  expect(exported.referencedMedia).toContain("media/native-test.png");
  expect(exported.referencedMedia).toContain("media/native-test.mp4");
});

test("latest news renders three articles and editor/aside layouts reflow at enlarged text", async ({ page }) => {
  await openEditor(page);
  await page.getByRole("button", { name: "Latest news", exact: true }).click();
  await expect(page.locator("#preview .site-menu a.active")).toHaveText("News");
  await expect(page.locator("#preview article")).toHaveCount(3);
  await expect(page.locator("#preview article").first().getByRole("heading")).toHaveText("A simpler way to publish");
  if (!await page.locator("#content-navigation").evaluate((details) => details.open)) await page.getByText("Choose content", { exact: true }).click();
  await page.getByRole("button", { name: "About", exact: true }).click();
  await page.locator("html").evaluate((element) => { element.style.fontSize = "250%"; });
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize)).toBe("40px");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test("homepage choice persists, hides its submenu link and reflows at enlarged text", async ({ page }) => {
  await openEditor(page);
  if (!await page.locator("#content-navigation").evaluate((details) => details.open)) await page.getByText("Choose content", { exact: true }).click();
  await page.locator("#pages button").filter({ hasText: "Team" }).click();
  await page.getByRole("button", { name: "Use as home page", exact: true }).click();
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.homePageId)).toBe(project.pages[0].children[0].id);
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  const aside = page.locator("#preview aside.subpage-navigation");
  await expect(aside).toHaveCount(0);
  await expect(page.locator("#preview .site-menu a.active")).toHaveAttribute("href", "about/");
  for (const size of ["125%", "250%"]) {
    await page.locator("html").evaluate((element, size) => { element.style.fontSize = size; }, size);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  }
});

async function openField(page, content = "<p>Hello from the phone</p>") {
  await page.addInitScript(({ photoSize, videoSize, content, project }) => {
    window.WebeasyNative = {
      request(serialized) {
        const { id, method, payload } = JSON.parse(serialized);
        if (method === "ready") window.WebeasyField.setValue(content);
        if (method === "change") window.__content = payload.content;
        if (method === "resize") window.__fieldHeight = payload.height;
        const result = method === "pageLinks" ? { pages: project.pages, homePageId: project.pages[0].id } : method === "pickMedia" ? (payload.kind === "image"
          ? { id: "field-image", filename: "native-test.png", mimeType: "image/png", originalName: "Phone photo.png", size: photoSize }
          : { id: "field-video", filename: "native-test.mp4", mimeType: "video/mp4", originalName: "Phone clip.mp4", size: videoSize }) : null;
        queueMicrotask(() => window.WebeasyBridge.receive({ id, result }));
      },
    };
  }, { photoSize: photo.length, videoSize: clip.length, content, project });
  await page.route("**/media/native-test.png", (route) => route.fulfill({ contentType: "image/png", body: photo }));
  await page.route("**/media/native-test.mp4", (route) => route.fulfill({ contentType: "video/mp4", body: clip }));
  await page.goto("/field.html");
}

test("native embed exposes only a rich-text field, reports edits/height, and formats text", async ({ page }) => {
  await openField(page);
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await expect(field).toHaveText("Hello from the phone");
  await expect(page.getByLabel("Title", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Slug", { exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Date", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => getComputedStyle(document.body).paddingTop)).toBe("0px");
  await field.fill("Native field edit");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("Native field edit");
  await field.evaluate((field) => {
    const range = document.createRange();
    range.selectNodeContents(field);
    const selection = window.getSelection();
    selection.removeAllRanges(); selection.addRange(range);
  });
  await page.getByRole("button", { name: "Bold", exact: true }).click();
  await expect(field.locator("b,strong")).toHaveText("Native field edit");
  await expect.poll(() => page.evaluate(() => window.__fieldHeight)).toBeGreaterThan(200);
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await page.getByLabel("Caption (optional)").fill("Native phone figure");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(field.locator("figure img")).toHaveAttribute("src", "media/native-test.png");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("<figcaption>Native phone figure</figcaption>");
  await page.getByRole("button", { name: "Phone video", exact: true }).click();
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(field.locator("figure video source")).toHaveAttribute("src", "media/native-test.mp4");
});

test("page links save and navigate in previews, and the logo returns home without a home menu item", async ({ page }) => {
  await openEditor(page);
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.fill("Visit our team");
  await field.evaluate((field) => {
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(field);
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.getByRole("button", { name: "Link", exact: true }).first().click();
  await page.getByLabel("Page or subpage").selectOption("about/team/");
  await page.getByRole("button", { name: "Insert link", exact: true }).click();
  await expect(field.locator("a")).toHaveAttribute("href", "about/team/");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.pages[0].content)).toContain('href="about/team/"');
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(page.locator('#preview .site-menu a[href="about/"]')).toHaveCount(0);
  const logo = page.locator("#preview .site-logo");
  await expect(logo).toHaveAttribute("href", "/");
  await expect.poll(() => logo.locator("img").evaluate((image) => image.naturalWidth)).toBeGreaterThan(0);
  const headerBox = await page.locator("#preview .site-header").boundingBox();
  const logoBox = await logo.boundingBox();
  const menuBox = await page.locator("#preview .site-menu").boundingBox();
  expect(Math.abs(logoBox.x - headerBox.x)).toBeLessThan(1);
  expect(Math.abs(menuBox.x + menuBox.width - headerBox.x - headerBox.width)).toBeLessThan(1);
  await page.locator("#preview .rich-content").getByRole("link", { name: "Visit our team", exact: true }).click();
  await expect(page.locator("#preview h1")).toHaveText(project.pages[0].children[0].title);
  await logo.click();
  await expect(page.locator("#preview h1")).toHaveText(project.pages[0].title);
  await page.locator("html").evaluate((element) => { element.style.fontSize = "250%"; });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test("native fields choose home/subpage links, edit and remove links, reject unsafe URLs and cancel unchanged", async ({ page }) => {
  await openField(page);
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.fill("Selected text");
  await field.evaluate((field) => {
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(field);
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await expect(page.getByLabel("Page or subpage").locator('option[value="about/team/"]')).toHaveText("About / Team");
  await page.getByLabel("Page or subpage").selectOption("index.html");
  await page.getByRole("button", { name: "Insert link", exact: true }).click();
  await expect(field.locator("a")).toHaveAttribute("href", "index.html");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain('href="index.html"');
  const selectLink = () => field.locator("a").evaluate((anchor) => {
    anchor.closest(".rich-editor").focus();
    getSelection().setBaseAndExtent(anchor.firstChild, 1, anchor.firstChild, 1);
  });
  await selectLink();
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await page.getByLabel("Link URL").fill("javascript:alert(1)");
  await page.getByRole("button", { name: "Save link", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByLabel("Link URL").fill("https://example.com/info");
  await page.getByRole("button", { name: "Save link", exact: true }).click();
  await expect(field.locator("a")).toHaveAttribute("href", "https://example.com/info");
  await expect(field.locator("a")).toHaveText("Selected text");
  await selectLink();
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await page.getByLabel("Link URL").fill("https://example.com/cancelled");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(field.locator("a")).toHaveAttribute("href", "https://example.com/info");
  await selectLink();
  await page.getByRole("button", { name: "Remove link", exact: true }).click();
  await expect(field.locator("a")).toHaveCount(0);
  await expect(field).toHaveText("Selected text");
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("<a");
});

test("inline marks toggle on the selected word and emphasis works at the caret", async ({ page }) => {
  await openField(page, "<p>alpha beta gamma</p>");
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.evaluate((field) => {
    field.focus();
    const text = field.querySelector("p").firstChild;
    getSelection().setBaseAndExtent(text, 6, text, 10);
  });
  const bold = page.getByRole("button", { name: "Bold", exact: true });
  await bold.click();
  await expect(field.locator("b,strong")).toHaveText("beta");
  await expect(bold).toHaveAttribute("aria-pressed", "true");
  await bold.click();
  await expect(field.locator("b,strong")).toHaveCount(0);
  const emphasis = page.getByRole("button", { name: "Emphasis", exact: true });
  await emphasis.click();
  await expect(field.locator("em")).toHaveText("beta");
  await emphasis.click();
  await expect(field.locator("em,i")).toHaveCount(0);
  await expect(field).toHaveText("alpha beta gamma");
  await field.evaluate((field) => {
    field.focus();
    const text = field.querySelector("p").lastChild;
    getSelection().setBaseAndExtent(text, text.length, text, text.length);
  });
  await emphasis.click();
  await page.keyboard.type(" typed");
  await expect(field.locator("em")).toContainText("typed");
  await expect(field).toHaveText("alpha beta gamma typed");
});

test("SE-sized keyboard viewport keeps media actions reachable after scrolling long content", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 240 });
  await openField(page, Array.from({ length: 30 }, (_, i) => `<p>Long content paragraph ${i}</p>`).join(""));
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.locator("p").last().scrollIntoViewIfNeeded();
  for (const name of ["Phone image", "Phone video"]) {
    const box = await page.getByRole("button", { name, exact: true }).boundingBox();
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.y + box.height).toBeLessThanOrEqual(240);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await page.getByLabel("Caption (optional)").fill("SE photo");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(field.locator("figure img")).toHaveAttribute("src", "media/native-test.png");
});

test("native generation engine normalizes content and renders portable website files", async ({ page }) => {
  await page.goto("/generator.html");
  const catalog = JSON.parse(await readFile(new URL("../dist/catalog.json", import.meta.url), "utf8"));
  const result = await page.evaluate(({ project, catalog }) =>
    JSON.parse(window.WebeasyGenerator.compile(JSON.stringify({ project, catalog }))), { project, catalog });
  expect(result.error).toBeUndefined();
  expect(result.files.some((file) => file.path === "about/team/index.html")).toBe(true);
  expect(result.files.find((file) => file.path === "news/index.html").html.match(/<article\b/g)).toHaveLength(3);
  const invalid = await page.evaluate(() => JSON.parse(window.WebeasyGenerator.normalize(JSON.stringify({
    version: 1, pages: [{ id: "invalid", title: "Bad slug", slug: "../escape", date: "2026-01-01" }], news: [], media: [],
  }))));
  expect(invalid.error).toBeTruthy();
});

test("hosting QR import validates the envelope and preserves credentials without putting them in content", async ({ page }) => {
  await page.goto("/generator.html");
  const result = await page.evaluate(() => {
    const connection = { protocol: "sftp", host: "example.test", port: 22, username: "editor", password: " test password ", directory: "/site" };
    const read = (payload) => JSON.parse(window.WebeasyGenerator.readConnection(JSON.stringify(payload)));
    return {
      valid: read({ kind: "webeasy-publish", version: 1, connection }),
      future: read({ kind: "webeasy-publish", version: 2, connection }),
      wrong: read({ kind: "other", version: 1, connection }),
      unsafe: read({ kind: "webeasy-publish", version: 1, connection: { ...connection, directory: "/../secret" } }),
    };
  });
  expect(result.valid.connection.password).toBe(" test password ");
  expect(result.valid.project).toBeUndefined();
  for (const key of ["future", "wrong", "unsafe"]) expect(result[key].error).toBeTruthy();
});

test("Rsbuild page layouts are selectable, saved and used by previews", async ({ page }) => {
  await openEditor(page);
  await page.getByLabel("Page layout", { exact: true }).selectOption("landing-page");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.pages[0].templateId)).toBe("landing-page");
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(page.locator("#preview .landing-layout")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
