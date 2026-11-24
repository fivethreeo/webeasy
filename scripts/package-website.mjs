import { cp, mkdir, readFile, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const source = path.join(root, "website/dist");
const catalog = JSON.parse(await readFile(path.join(source, "catalog.json"), "utf8"));
for (const entry of [...catalog.templates, ...catalog.components]) {
  await readFile(path.join(source, entry.preview));
}
for (const relative of [
  "apps/ios/Resources/Website",
  "apps/android/app/build/generated/websiteAssets/website",
]) {
  const destination = path.join(root, relative);
  await rm(destination, { recursive: true, force: true });
  await mkdir(path.dirname(destination), { recursive: true });
  await cp(source, destination, { recursive: true });
  console.log(`Packaged ${relative}`);
}
