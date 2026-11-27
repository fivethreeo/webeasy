import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const rendererRoot = fileURLToPath(new URL("../shared/renderer/", import.meta.url));
const dependencies = JSON.parse(readFileSync(path.join(rendererRoot, "html-dependencies.json"), "utf8"));

function cSources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? cSources(file) : entry.name.endsWith(".c") ? [file] : [];
  }).sort();
}

// Keep native tests and WASM on the same implementation and pinned C libraries.
// utf8proc_data.c is included by utf8proc.c, not a separate translation unit.
export function rendererSources() {
  return ["renderer.c", "site.c", "vendor/mustach/mustach.c",
    "vendor/cjson/cJSON.c", ...dependencies.sources, ...dependencies.platformSources.posix]
    .map((file) => path.join(rendererRoot, file))
    .concat(dependencies.recursiveSourceDirectories.flatMap((directory) => cSources(path.join(rendererRoot, directory))));
}

export const rendererFlags = [
  "-std=c11", "-DNO_OPEN_MEMSTREAM", "-DLEXBOR_STATIC", "-DUTF8PROC_STATIC",
  ...["", "vendor/mustach", "vendor/cjson", "vendor/utf8proc", "vendor/lexbor/source"]
    .map((directory) => `-I${path.join(rendererRoot, directory)}`),
];
