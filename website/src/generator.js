import { createRichTextSanitizer } from "./rich-text.js";
import { normalizeProject, renderSite, referencedMedia } from "./content-model.js";

const sanitize = createRichTextSanitizer(window);
function result(action) {
  try { return JSON.stringify(action()); }
  catch (error) { return JSON.stringify({ error: error.message }); }
}
window.WebeasyGenerator = {
  normalize(serialized) {
    return result(() => ({ project: normalizeProject(JSON.parse(serialized), sanitize) }));
  },
  compile(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      const project = normalizeProject(input.project, sanitize);
      return { project, files: renderSite(project, input.catalog), referencedMedia: referencedMedia(project, window) };
    });
  },
};
