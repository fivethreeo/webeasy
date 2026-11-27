import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));

async function files(directory, prefix = "") {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = path.join(prefix, entry.name);
    if (entry.isDirectory()) result.push(...await files(path.join(directory, entry.name), relative));
    else result.push(relative);
  }
  return result.sort();
}

test("native packages contain the entire generated site byte-for-byte", async () => {
  const build = spawnSync("npm", ["run", "package:website"], { cwd: root, encoding: "utf8" });
  assert.equal(build.status, 0, build.stdout + build.stderr);
  const source = path.join(root, "website/dist");
  const expected = await files(source);
  assert.ok(expected.includes("assets/site.css"));
  assert.ok(expected.includes("assets/site.js"));
  for (const file of ["editor.html", "assets/editor.js", "assets/editor.css", "field.html", "assets/field.js", "assets/field.css",
    "generator.html", "assets/generator.js", "content.json", "storage.sql", "renderer-licenses.txt",
    "about/team/index.html", "news/index.html", "news/2026-11-24/simpler-publishing/index.html"]) assert.ok(expected.includes(file), file);
  assert.ok(!expected.includes("provision.html"));
  assert.ok(!expected.includes("assets/provision.js"));
  assert.ok(!expected.includes("assets/provision.css"));
  for (const file of expected.filter((name) => /\.(html|js|json)$/.test(name))) {
    const text = await readFile(path.join(source, file), "utf8");
    assert.ok(!text.includes("__WebeasyLocalConnection"), `${file} unexpectedly contains local provisioning data`);
    assert.ok(!text.includes("webeasy-publish") || file === "assets/generator.js",
      `${file} unexpectedly contains QR generation code`);
  }
  for (const target of [
    "apps/ios/Resources/Website",
    "apps/android/app/build/generated/websiteAssets/website",
  ]) {
    const starter = path.join(root, "shared/starter-site");
    const starterFiles = await files(starter);
    assert.deepEqual(await files(path.join(root, target)), [...expected, ...starterFiles.map(file => `starter/${file}`)].sort());
    for (const file of expected) {
      assert.deepEqual(await readFile(path.join(root, target, file)), await readFile(path.join(source, file)));
    }
    for (const file of starterFiles) {
      assert.deepEqual(await readFile(path.join(root, target, "starter", file)), await readFile(path.join(starter, file)));
    }
    const project = JSON.parse(await readFile(path.join(starter, "content.json"), "utf8"));
    for (const media of project.media) {
      assert.equal((await readFile(path.join(starter, "media", media.filename))).length, media.size);
    }
    const metadata = JSON.parse(await readFile(path.join(starter, "metadata.json"), "utf8"));
    assert.ok(Object.keys(metadata).every(key => ["template_source_index", "asset_overrides"].includes(key)
      || key.startsWith("template_source/") || key.startsWith("asset_chunk/")));
    for (const [file, asset] of Object.entries(JSON.parse(metadata.asset_overrides))) {
      const bytes = Buffer.concat(Array.from({ length: asset.chunks }, (_, index) =>
        Buffer.from(metadata[`asset_chunk/${file}/${index}`], "base64")));
      assert.equal(bytes.length, asset.size);
    }
  }
  const catalog = JSON.parse(await readFile(path.join(source, "catalog.json"), "utf8"));
  for (const entry of [...catalog.templates, ...catalog.components]) {
    const html = await readFile(path.join(source, entry.preview), "utf8");
    assert.ok(!html.includes("{{"), `${entry.preview} has unresolved placeholders`);
    assert.ok(html.includes('<base href="../">'));
    assert.ok(html.includes('src="assets/site.js"'));
    assert.ok(!html.includes("Generated HTML"));
    assert.ok(!html.includes("<pre>"));
  }
});
