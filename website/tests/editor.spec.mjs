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

test("native generator reports changes even when the current draft is incomplete", async ({ page }) => {
  await page.goto("/generator.html");
  await page.waitForFunction(() => typeof window.WebeasyGenerator?.changes === "function");
  const result = await page.evaluate((before) => {
    const after = structuredClone(before);
    after.news[0].title = "";
    return JSON.parse(window.WebeasyGenerator.changes(JSON.stringify({ before, after })));
  }, project);
  expect(result.changes).toHaveLength(1);
  expect(result.changes[0]).toMatchObject({
    id: project.news[0].id, status: "Changed",
    fields: [{ name: "Title", before: project.news[0].title, after: "" }],
  });
});

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

async function openField(page, content = "<p>Hello from the phone</p>", nativeDetails = null, fieldOptions = {}) {
  await page.addInitScript(({ photoSize, videoSize, content, project, nativeDetails, fieldOptions }) => {
    window.WebeasyNative = {
      request(serialized) {
        const { id, method, payload } = JSON.parse(serialized);
        if (method === "ready") window.WebeasyField.setValue(content, fieldOptions);
        if (method === "change") window.__content = payload.content;
        if (method === "resize") window.__fieldHeight = payload.height;
        if (method === "pickMedia") window.__mediaRequest = payload;
        if (method === "mediaDetails") window.__mediaDetailsRequest = payload;
        if (method === "linkDetails") {
          window.__linkDetailsRequest = payload;
          window.__replyLinkDetails = (result) => window.WebeasyBridge.receive({ id, result });
          return;
        }
        const result = method === "ready" && nativeDetails ? { mediaDetails: true, linkDetails: nativeDetails.linkDetails === true }
          : method === "mediaDetails" ? nativeDetails.result
          : method === "pageLinks" ? { pages: project.pages, homePageId: project.pages[0].id } : method === "pickMedia" ? (payload.kind === "image"
          ? { id: "field-image", filename: payload.basename ? `${payload.basename}.png` : "native-test.png", mimeType: "image/png", originalName: "Phone photo.png", size: photoSize }
          : { id: "field-video", filename: payload.basename ? `${payload.basename}.mp4` : "native-test.mp4", mimeType: "video/mp4", originalName: "Phone clip.mp4", size: videoSize }) : null;
        queueMicrotask(() => window.WebeasyBridge.receive({ id, result }));
      },
    };
  }, { photoSize: photo.length, videoSize: clip.length, content, project, nativeDetails, fieldOptions });
  await page.route("**/media/native-test.png", (route) => route.fulfill({ contentType: "image/png", body: photo }));
  await page.route("**/media/native-test.mp4", (route) => route.fulfill({ contentType: "video/mp4", body: clip }));
  await page.goto("/field.html");
}

test("native link views preserve selection, formatted text, cancellation and removal", async ({ page }) => {
  await openField(page, "<p>Before <strong>our team</strong> after</p>", { linkDetails: true });
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.evaluate((field) => {
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(field.querySelector("strong"));
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__linkDetailsRequest?.text)).toBe("our team");
  expect(await page.evaluate(() => window.__linkDetailsRequest)).toMatchObject({
    editing: false, textReadOnly: true, href: "", pages: expect.arrayContaining([expect.objectContaining({ href: "index.html" })]),
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await field.evaluate((field) => getSelection().setBaseAndExtent(field, 0, field, 0));
  await page.evaluate(() => window.__replyLinkDetails({ href: "about/team/", text: "our team" }));
  await expect(field.locator("a")).toHaveText("our team");
  await expect(field.locator("strong")).toHaveText("our team");
  await expect(field.locator("a strong, strong a")).toHaveCount(1);
  await expect(field).toHaveText("Before our team after");
  for (const response of [null, { href: "javascript:alert(1)", text: "unsafe" }, { remove: true }]) {
    await field.evaluate((field) => {
      window.__linkDetailsRequest = null;
      field.focus();
      const range = document.createRange();
      range.selectNode(field.querySelector("a"));
      getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await page.getByRole("button", { name: "Link", exact: true }).click();
    await expect.poll(() => page.evaluate(() => window.__linkDetailsRequest?.editing)).toBe(true);
    await page.evaluate((response) => window.__replyLinkDetails(response), response);
    if (response?.remove) await expect(field.locator("a")).toHaveCount(0);
    else await expect(field.locator("a")).toHaveAttribute("href", "about/team/");
    await expect(field.locator("strong")).toHaveText("our team");
    await expect(field).toHaveText("Before our team after");
  }
  expect(await page.evaluate(() => ["about/", "https://example.com", "mailto:test@example.com", "tel:123", "../private", "javascript:alert(1)", ""]
    .map((href) => window.WebeasyField.validLink(href)))).toEqual([true, true, true, true, false, false, false]);
});

test("short news fields retain formatting but omit structural and media controls and clean pasted blocks", async ({ page }) => {
  await openField(page, "<h2>Heading</h2><p><strong>Bold</strong> text</p>", null, { paragraphsOnly: true });
  const field = page.locator(".rich-editor");
  await expect(field.locator("h2")).toHaveCount(0);
  await expect(field.locator("strong")).toHaveText("Bold");
  for (const label of ["Phone image", "Phone video", "Bullets", "Numbers", "Quote", "Remove figure"]) {
    await expect(page.getByRole("button", { name: label, exact: true })).toHaveCount(0);
  }
  await expect(page.getByRole("button", { name: "Bold", exact: true }).locator("svg")).toHaveCount(1);
  await expect(page.getByLabel("Paragraph or heading")).toHaveCount(0);
  await field.focus();
  await field.evaluate((field) => {
    const transfer = new DataTransfer();
    transfer.setData("text/html", '<h3>Pasted heading</h3><ul><li><em>List text</em></li></ul><img src="media/native-test.png">');
    field.dispatchEvent(new ClipboardEvent("paste", { clipboardData: transfer, bubbles: true, cancelable: true }));
  });
  await expect(field.locator("h3,ul,li,img,figure")).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("Pasted heading");
});

test("simple image defaults reset to 400 pixels and 85 percent while advanced remains optional", async ({ page }) => {
  await openField(page, "<p>Image defaults</p>", null, { simple: true });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.locator("#image-width")).toHaveValue("400");
  await expect(page.locator("#image-quality")).toHaveValue("85");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.locator("#image-width")).toHaveValue("400");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.evaluate(() => window.WebeasyField.setValue("<p>Advanced defaults</p>", { simple: false }));
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.locator("#image-width")).toHaveValue("");
  await expect(page.locator("#image-quality")).toHaveValue("");
});

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

test("optional basenames are validated before the picker and native filenames are used in figures", async ({ page }) => {
  await openField(page);
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await page.getByLabel("File basename (optional)").fill("../private");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(await page.evaluate(() => window.__mediaRequest)).toBeUndefined();
  await page.getByLabel("File basename (optional)").fill("coast");
  await page.getByLabel("Caption (optional)").fill("Our coast");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(field.locator("figure img")).toHaveAttribute("src", "media/coast.png");
  expect(await page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "coast" });
  await page.getByRole("button", { name: "Phone video", exact: true }).click();
  await expect(page.getByLabel("File basename (optional)")).toHaveValue("");
  await page.getByLabel("File basename (optional)").fill("shore");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect(field.locator("figure video source")).toHaveAttribute("src", "media/shore.mp4");
  expect(await page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "video", basename: "shore" });
  await expect.poll(() => page.evaluate(() => window.__content)).toContain('src="media/shore.mp4"');
});

test("optional image width and quality validate before picking and never carry into videos", async ({ page }) => {
  await openField(page);
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await page.getByLabel("Maximum image width (pixels, optional)").fill("0");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  expect(await page.evaluate(() => window.__mediaRequest)).toBeUndefined();
  await page.getByLabel("Maximum image width (pixels, optional)").fill("1600");
  await page.getByLabel("JPEG quality (%, optional)").fill("101");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  expect(await page.evaluate(() => window.__mediaRequest)).toBeUndefined();
  await page.getByLabel("JPEG quality (%, optional)").fill("80");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "", width: 1600, quality: 80 });
  await page.getByRole("button", { name: "Phone video", exact: true }).click();
  await expect(page.getByLabel("JPEG quality (%, optional)")).toBeHidden();
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "video", basename: "" });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.getByLabel("Maximum image width (pixels, optional)")).toHaveValue("");
  await page.getByLabel("JPEG quality (%, optional)").fill("0");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
});

test("common image presets default to None, allow custom values, and reset between images", async ({ page }) => {
  await openField(page);
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  const widths = page.getByLabel("Common image widths");
  const qualities = page.getByLabel("Common JPEG qualities");
  await expect(widths).toHaveValue("");
  await expect(qualities).toHaveValue("");
  await widths.selectOption("1280");
  await qualities.selectOption("85");
  await expect(page.getByLabel("Maximum image width (pixels, optional)")).toHaveValue("1280");
  await expect(page.getByLabel("JPEG quality (%, optional)")).toHaveValue("85");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "", width: 1280, quality: 85 });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(widths).toHaveValue("");
  await expect(qualities).toHaveValue("");
  await widths.selectOption("1600");
  await qualities.selectOption("90");
  await widths.selectOption("");
  await qualities.selectOption("");
  await expect(page.getByLabel("Maximum image width (pixels, optional)")).toHaveValue("");
  await expect(page.getByLabel("JPEG quality (%, optional)")).toHaveValue("");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "" });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await page.getByLabel("Maximum image width (pixels, optional)").fill("1400");
  await page.getByLabel("JPEG quality (%, optional)").fill("82");
  await expect(widths).toHaveValue("custom");
  await expect(qualities).toHaveValue("custom");
  await page.getByRole("button", { name: "Insert figure", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "", width: 1400, quality: 82 });
});

test("native full-screen media details return optional optimization without opening an embedded dialog", async ({ page }) => {
  await openField(page, "<p>Hello</p>", {
    result: { basename: "coast", alt: "Coast", caption: "Our coast", imageOptions: { width: 1200, quality: 82 } },
  });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Content", exact: true }).locator("figure img")).toHaveAttribute("src", "media/coast.png");
  expect(await page.evaluate(() => window.__mediaDetailsRequest)).toEqual({ kind: "image" });
  expect(await page.evaluate(() => window.__mediaRequest)).toEqual({ kind: "image", basename: "coast", width: 1200, quality: 82 });
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("<figcaption>Our coast</figcaption>");
});

test("cancelling native full-screen media details leaves content and the picker untouched", async ({ page }) => {
  await openField(page, "<p>Keep this</p>", { result: null });
  await page.getByRole("button", { name: "Phone image", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Figure cancelled.");
  expect(await page.evaluate(() => window.__mediaRequest)).toBeUndefined();
  await expect(page.getByRole("textbox", { name: "Content", exact: true })).toHaveText("Keep this");
});

test("image and video figures can be removed without deleting surrounding content", async ({ page, isMobile }) => {
  await openField(page, '<p>Before</p><figure><img src="assets/coast.svg" alt="Coast"><figcaption>Our coast</figcaption></figure><p>Between</p><figure><video src="media/native-test.mp4"></video><figcaption>Our video</figcaption></figure><p>After</p>');
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const activate = (locator) => isMobile ? locator.tap() : locator.click();
  await expect(page.getByRole("button", { name: "Remove figure", exact: true })).toHaveCount(0);
  await activate(field.locator("img"));
  await expect(field.locator("figure[data-editor-selected]")).toHaveCount(1);
  await page.keyboard.press("Backspace");
  await expect(field.locator("img")).toHaveCount(0);
  await expect(field.locator("video")).toHaveCount(1);
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("assets/coast.svg");
  await activate(field.locator("figcaption"));
  await page.keyboard.press("End");
  await page.keyboard.type(" caption");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("<figcaption>Our video caption</figcaption>");
  expect(await page.evaluate(() => window.__content)).not.toContain("data-editor-selected");
  const video = field.locator("video");
  if (isMobile) await video.tap({ position: { x: 8, y: 8 } });
  else await video.click({ position: { x: 8, y: 8 } });
  expect(await field.evaluate(() => getSelection().isCollapsed)).toBe(false);
  await page.keyboard.press("Delete");
  await expect(field.locator("figure")).toHaveCount(0);
  await expect(field.locator("p")).toHaveText(["Before", "Between", "After"]);
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("<figure");
});

test("an uncaptioned figure can be removed and the empty field stays editable", async ({ page, isMobile }) => {
  await openField(page, '<figure><img src="assets/coast.svg" alt="Coast"></figure>');
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const activate = (locator) => isMobile ? locator.tap() : locator.click();
  await activate(field.locator("img"));
  await page.keyboard.press("Backspace");
  await expect(field.locator("figure")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Remove figure", exact: true })).toHaveCount(0);
  await page.keyboard.type("Still editable");
  await expect(field).toHaveText("Still editable");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("Still editable");
});

test("phone deletion input removes only a fully selected figure, not selected caption text", async ({ page, isMobile }) => {
  await openField(page, '<p>Before</p><figure><img src="assets/coast.svg" alt="Coast"><figcaption>Our coast</figcaption></figure><p>After</p>');
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const caption = field.locator("figcaption");
  await caption.evaluate((caption) => {
    const field = caption.closest(".rich-editor");
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(caption);
    getSelection().removeAllRanges(); getSelection().addRange(range);
    field.dispatchEvent(new Event("editor-selection"));
    const input = new InputEvent("beforeinput", { inputType: "deleteContentBackward", bubbles: true, cancelable: true });
    field.dispatchEvent(input);
    window.__captionDeletionPrevented = input.defaultPrevented;
  });
  expect(await page.evaluate(() => window.__captionDeletionPrevented)).toBe(false);
  await expect(field.locator("figure")).toHaveCount(1);
  if (isMobile) await field.locator("img").tap(); else await field.locator("img").click();
  await field.locator("img").evaluate((image) => {
    const range = document.createRange();
    range.selectNode(image);
    getSelection().removeAllRanges(); getSelection().addRange(range);
    image.closest(".rich-editor").dispatchEvent(new Event("editor-selection"));
  });
  await field.evaluate((field) => field.dispatchEvent(new InputEvent("beforeinput", {
    inputType: "deleteContentBackward", bubbles: true, cancelable: true,
  })));
  await expect(field.locator("figure")).toHaveCount(0);
  await expect(field.locator("p")).toHaveText(["Before", "After"]);
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("<figure");
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
  await expect(page.getByLabel("Link text", { exact: true })).toHaveValue("Visit our team");
  await expect(page.getByLabel("Link text", { exact: true })).toHaveAttribute("readonly", "");
  await page.getByLabel("Page or subpage").selectOption("about/team/");
  await page.getByRole("button", { name: "Insert link", exact: true }).click();
  await expect(field.locator("a")).toHaveAttribute("href", "about/team/");
  await expect(field.locator("a")).toHaveText("Visit our team");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.__savedProject.pages[0].content)).toContain('href="about/team/"');
  await page.getByRole("button", { name: "Preview", exact: true }).click();
  await expect(page.locator('#preview .site-menu a[href="about/"]')).toHaveCount(0);
  const logo = page.locator("#preview .site-logo");
  await expect(logo).toHaveAttribute("href", "./");
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
  await expect(page.getByRole("button", { name: "Remove link", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await page.getByRole("button", { name: "Remove link", exact: true }).click();
  await expect(field.locator("a")).toHaveCount(0);
  await expect(field).toHaveText("Selected text");
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("<a");
});

test("Link edits a selected formatted anchor and removes it without losing formatting even with an invalid URL", async ({ page, isMobile }) => {
  await openField(page, '<p>Before <a href="https://example.com"><strong>our team</strong></a> after</p>');
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const anchor = field.locator("a");
  if (isMobile) await anchor.tap(); else await anchor.click();
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Edit link", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await anchor.evaluate((anchor) => {
    anchor.closest(".rich-editor").focus();
    const range = document.createRange();
    range.selectNode(anchor);
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Edit link", exact: true })).toBeVisible();
  await page.getByLabel("Link URL").fill("javascript:alert(1)");
  await page.getByRole("button", { name: "Remove link", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(field.locator("a")).toHaveCount(0);
  await expect(field.locator("strong")).toHaveText("our team");
  await expect(field).toHaveText("Before our team after");
  await expect.poll(() => page.evaluate(() => window.__content)).not.toContain("<a");
});

test("adding a link wraps selected formatted text without replacing its surrounding content", async ({ page }) => {
  await openField(page, "<p>Before <strong>our team</strong> after</p>");
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await field.evaluate((field) => {
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(field.querySelector("strong"));
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await expect(page.getByLabel("Link text", { exact: true })).toHaveValue("our team");
  await page.getByLabel("Page or subpage").selectOption("about/team/");
  await page.getByRole("button", { name: "Insert link", exact: true }).click();
  await expect(field.locator("a")).toHaveText("our team");
  await expect(field.locator("a")).toHaveAttribute("href", "about/team/");
  await expect(field.locator("strong")).toHaveText("our team");
  await expect(field).toHaveText("Before our team after");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain('href="about/team/"');
});

test("selected-text links keep their original range through native replies and dialog focus changes", async ({ page }) => {
  await openField(page, "<p>Before <strong>our team</strong> after</p>");
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  await page.evaluate(() => {
    const request = window.WebeasyNative.request;
    window.WebeasyNative.request = (serialized) => {
      if (JSON.parse(serialized).method === "pageLinks") window.__replyPageLinks = () => request(serialized);
      else request(serialized);
    };
  });
  await field.evaluate((field) => {
    field.focus();
    const range = document.createRange();
    range.selectNodeContents(field.querySelector("strong"));
    getSelection().removeAllRanges(); getSelection().addRange(range);
  });
  const collapseSelection = () => field.evaluate((field) => {
    getSelection().setBaseAndExtent(field, 0, field, 0);
    document.dispatchEvent(new Event("selectionchange"));
  });
  await page.getByRole("button", { name: "Link", exact: true }).click();
  await collapseSelection();
  await page.evaluate(() => window.__replyPageLinks());
  await expect(page.getByLabel("Link text", { exact: true })).toHaveValue("our team");
  await page.getByLabel("Page or subpage").selectOption("about/team/");
  await collapseSelection();
  await page.getByRole("button", { name: "Insert link", exact: true }).click();
  await expect(field.locator("a")).toHaveText("our team");
  await expect(field.locator("a")).toHaveAttribute("href", "about/team/");
  await expect(field.locator("strong")).toHaveText("our team");
  await expect(field).toHaveText("Before our team after");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain('href="about/team/"');
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
  for (const name of ["Emphasis", "Strike", "Underline"]) {
    await expect(page.getByRole("button", { name, exact: true }).locator("svg")).toHaveCount(1);
  }
  for (const [name, selector] of [["Strike", "s,strike,del"], ["Underline", "u"]]) {
    const control = page.getByRole("button", { name, exact: true });
    await control.click();
    await expect(field.locator(selector)).toHaveText("beta");
    await control.click();
    await expect(field.locator(selector)).toHaveCount(0);
  }
  await emphasis.click();
  await expect(field.locator("em")).toHaveText("beta");
  await expect(emphasis).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Italic", exact: true })).toHaveAttribute("aria-pressed", "false");
  await emphasis.click();
  await expect(field.locator("em,i")).toHaveCount(0);
  await expect(field).toHaveText("alpha beta gamma");
  await field.evaluate((field) => {
    field.focus();
    const text = field.querySelector("p").lastChild;
    getSelection().setBaseAndExtent(text, text.length, text, text.length);
  });
  await emphasis.click();
  await expect(emphasis).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Italic", exact: true })).toHaveAttribute("aria-pressed", "false");
  await page.keyboard.type(" typed");
  await expect(field.locator("em")).toContainText("typed");
  await expect(field).toHaveText("alpha beta gamma typed");
});

test("toolbar highlights markup rather than inherited italic and link styling", async ({ page }) => {
  await openField(page, '<p><i>italic</i> <em>emphasis</em> <a href="about/">link</a> <a href="about/"><u>underlined link</u></a> <i><em>both</em></i> plain</p>');
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const names = ["Italic", "Emphasis", "Link", "Underline"];
  for (const [selector, active] of [
    ["i:first-child", ["Italic"]],
    ["p > em", ["Emphasis"]],
    ["a:first-of-type", ["Link"]],
    ["u", ["Link", "Underline"]],
    ["i > em", ["Italic", "Emphasis"]],
    ["p", []],
  ]) {
    for (const caret of [false, true]) {
      await field.evaluate((field, { selector, caret }) => {
        field.focus();
        const node = field.querySelector(selector);
        const range = document.createRange();
        range.selectNodeContents(node);
        if (caret) {
          if (selector === "p") range.collapse(false);
          else range.setStart(node.firstChild, 1);
          range.collapse(true);
        }
        getSelection().removeAllRanges(); getSelection().addRange(range);
        field.dispatchEvent(new Event("editor-selection"));
      }, { selector, caret });
      for (const name of names) {
        await expect(page.getByRole("button", { name, exact: true })).toHaveAttribute("aria-pressed", String(active.includes(name)));
      }
    }
  }
});

test("full rich fields retain list controls while paragraph-only fields omit them", async ({ page }) => {
  await openField(page, "<p>List item</p>");
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  for (const name of ["Bullets", "Numbers"]) {
    await expect(page.getByRole("button", { name, exact: true }).locator("svg")).toHaveCount(1);
  }
  await field.focus();
  await page.getByRole("button", { name: "Bullets", exact: true }).click();
  await expect(field.locator("ul li")).toHaveText("List item");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("<ul>");
});

test("compact toolbar stays on one scrollable line at narrow widths and enlarged text", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await openField(page);
  const toolbar = page.getByRole("group", { name: "Content formatting", exact: true });
  for (const size of ["125%", "250%"]) {
    await page.getByRole("textbox", { name: "Content", exact: true }).focus();
    await page.locator("html").evaluate((element, size) => { element.style.fontSize = size; }, size);
    const layout = await toolbar.evaluate((toolbar) => {
      const controls = [...toolbar.querySelectorAll("button,select")];
      return {
        centers: controls.map((control) => {
          const rect = control.getBoundingClientRect();
          return rect.y + rect.height / 2;
        }),
        padding: parseFloat(getComputedStyle(toolbar.querySelector(".icon-button")).paddingLeft),
        fontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
        overflow: toolbar.scrollWidth > toolbar.clientWidth,
        pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      };
    });
    expect(Math.max(...layout.centers) - Math.min(...layout.centers)).toBeLessThanOrEqual(1);
    expect(layout.padding).toBeLessThanOrEqual(layout.fontSize * 0.15);
    expect(layout.overflow).toBe(true);
    expect(layout.pageOverflow).toBe(false);
    await page.getByLabel("Paragraph or heading").focus();
    const heading = await page.getByLabel("Paragraph or heading").boundingBox();
    const strip = await toolbar.boundingBox();
    expect(heading.x).toBeGreaterThanOrEqual(strip.x - 1);
    expect(heading.x + heading.width).toBeLessThanOrEqual(strip.x + strip.width + 1);
  }
});

test("Prosango quote icon keeps its accessible name and applies blockquote formatting", async ({ page }) => {
  await openField(page, "<p>Quoted text</p>");
  const field = page.getByRole("textbox", { name: "Content", exact: true });
  const quote = page.getByRole("button", { name: "Quote", exact: true });
  await expect(quote.locator("svg")).toHaveAttribute("aria-hidden", "true");
  await field.focus();
  await quote.click();
  await expect(field.locator("blockquote")).toHaveText("Quoted text");
  await expect.poll(() => page.evaluate(() => window.__content)).toContain("<blockquote>");
});

test("toolbar icons are larger on phones and extra large on tablets without wrapping", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await openField(page);
  const toolbar = page.getByRole("group", { name: "Content formatting", exact: true });
  const measure = () => toolbar.evaluate((toolbar) => {
    const icon = toolbar.querySelector(".editor-icon").getBoundingClientRect();
    const centers = [...toolbar.querySelectorAll("button,select")].map((control) => {
      const rect = control.getBoundingClientRect();
      return rect.y + rect.height / 2;
    });
    return { width: icon.width, height: icon.height,
      fontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
      spread: Math.max(...centers) - Math.min(...centers),
      pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth };
  });
  const phone = await measure();
  expect(phone.width).toBeGreaterThanOrEqual(phone.fontSize * 2);
  expect(phone.height).toBe(phone.width);
  expect(phone.spread).toBeLessThanOrEqual(1);
  await page.setViewportSize({ width: 1280, height: 800 });
  const tablet = await measure();
  expect(tablet.width).toBeGreaterThan(phone.width);
  expect(tablet.width).toBeGreaterThanOrEqual(tablet.fontSize * 3);
  expect(tablet.height).toBe(tablet.width);
  expect(tablet.spread).toBeLessThanOrEqual(1);
  expect(tablet.pageOverflow).toBe(false);
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

test("native generator loads server templates as a batch and exports their literal source", async ({ page }) => {
  const [catalog, project] = await Promise.all([
    page.request.get("/catalog.json").then((response) => response.json()),
    page.request.get("/content.json").then((response) => response.json()),
  ]);
  await page.goto("/generator.html");
  const result = await page.evaluate(({ catalog, project }) => {
    const sources = JSON.parse(window.WebeasyGenerator.templates(JSON.stringify(catalog))).sources;
    sources["webeasy_templates/templates/master.html"] = sources["webeasy_templates/templates/master.html"]
      .replace("<body>", '<body data-remote-master="yes">');
    const loaded = JSON.parse(window.WebeasyGenerator.loadTemplates(JSON.stringify({ catalog, sources, project })));
    const compiled = JSON.parse(window.WebeasyGenerator.compile(JSON.stringify({ project, catalog: loaded.catalog })));
    const preview = JSON.parse(window.WebeasyGenerator.previewTemplate(JSON.stringify({ catalog: loaded.catalog, id: "master" })));
    delete sources["webeasy_templates/templates/page.html"];
    const rejected = JSON.parse(window.WebeasyGenerator.loadTemplates(JSON.stringify({ catalog, sources, project })));
    return {
      error: loaded.error ?? null,
      home: compiled.files.find((file) => file.path === "index.html").html,
      source: compiled.files.find((file) => file.path === "webeasy_templates/templates/master.html").html,
      preview: preview.files[0].html,
      rejected: rejected.error,
      original: catalog.templates.find((entry) => entry.id === "master").source,
    };
  }, { catalog, project });
  expect(result.error).toBeNull();
  expect(result.home).toContain('data-remote-master="yes"');
  expect(result.source).toContain("{{{navigation}}}");
  expect(result.preview).toContain('data-remote-master="yes"');
  expect(result.rejected).toBeTruthy();
  expect(result.original).not.toContain("data-remote-master");
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

test("native publishing settings validate independently and compiled links use the public prefix", async ({ page }) => {
  await page.goto("/generator.html");
  const catalog = JSON.parse(await readFile(new URL("../dist/catalog.json", import.meta.url), "utf8"));
  const result = await page.evaluate(({ project, catalog }) => {
    const settings = JSON.parse(window.WebeasyGenerator.readPublishingSettings(JSON.stringify({
      publicPathPrefix: "/host/site", uploadSubfolder: "site/",
    })));
    const compiled = JSON.parse(window.WebeasyGenerator.compile(JSON.stringify({
      project, catalog, publicPathPrefix: settings.settings.publicPathPrefix,
    })));
    const invalid = JSON.parse(window.WebeasyGenerator.readPublishingSettings(JSON.stringify({
      publicPathPrefix: "/host/", uploadSubfolder: "../outside",
    })));
    return { settings, compiled, invalid };
  }, { project, catalog });
  expect(result.settings.settings).toEqual({ publicPathPrefix: "/host/site/", uploadSubfolder: "site" });
  expect(result.compiled.error).toBeUndefined();
  expect(result.compiled.files.find((file) => file.path === "index.html").html).toContain('class="site-logo" href="/host/site/"');
  expect(result.compiled.project.publicPathPrefix).toBeUndefined();
  expect(result.compiled.project.uploadSubfolder).toBeUndefined();
  expect(result.invalid.error).toContain("relative upload");
});

test("the Rsbuild example logo opens the example homepage rather than the catalog", async ({ page }) => {
  await page.goto("/about/");
  await expect(page.locator(".site-logo")).toHaveAttribute("href", "home.html");
  await page.locator(".site-logo").click();
  await expect(page).toHaveURL(/\/home\.html$/);
  await expect(page.locator("h1")).toHaveText(project.pages[0].title);
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
