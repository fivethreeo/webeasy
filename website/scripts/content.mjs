import { readFile } from "node:fs/promises";
import path from "node:path";
import { JSDOM } from "jsdom";
import { createRichTextSanitizer } from "../src/rich-text.js";
import { normalizeProject, referencedMedia } from "../src/content-model.js";

export const contentWindow = new JSDOM("").window;
export const sanitizeRichText = createRichTextSanitizer(contentWindow);

export async function loadProject(source) {
  const input = JSON.parse(await readFile(path.join(source, "content.json"), "utf8"));
  const project = normalizeProject(input, sanitizeRichText);
  for (const media of referencedMedia(project, contentWindow)) await readFile(path.join(source, media));
  return project;
}
