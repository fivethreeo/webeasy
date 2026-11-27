import { mkdirSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { rendererSources, rendererFlags } from "./renderer-sources.mjs";

// A standalone static library lets macOS test the real Swift/C boundary without
// launching an app. Numeric object names avoid collisions between library files
// such as html/parser.c and css/parser.c.
const directory = path.resolve("dist/renderer/native-lib");
mkdirSync(directory, { recursive: true });
const objects = rendererSources().map((source, index) => {
  const object = path.join(directory, `${index}.o`);
  const result = spawnSync(process.env.CC || "cc",
    [...rendererFlags, "-O2", "-c", source, "-o", object], { stdio: "inherit" });
  if (result.error || result.status !== 0) process.exit(result.status || 1);
  return object;
});
const library = path.join(directory, "libwebeasy.a");
rmSync(library, { force: true });
const archive = spawnSync("ar", ["rcs", library, ...objects], { stdio: "inherit" });
if (archive.error || archive.status !== 0) process.exit(archive.status || 1);
