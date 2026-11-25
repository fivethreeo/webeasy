import { createRichTextSanitizer } from "./rich-text.js";
import { normalizeProject, renderSite, referencedMedia } from "./content-model.js";
import { readConnectionQR } from "./connection.mjs";
import { normalizePublishingSettings } from "./publishing-settings.js";
import { projectChanges } from "./content-changes.js";

const sanitize = createRichTextSanitizer(window);
function result(action) {
  try { return JSON.stringify(action()); }
  catch (error) { return JSON.stringify({ error: error.message }); }
}
window.WebeasyGenerator = {
  changes(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      return { changes: projectChanges(input.before, input.after) };
    });
  },
  readConnection(serialized) {
    return result(() => ({ connection: readConnectionQR(serialized) }));
  },
  readPublishingSettings(serialized) {
    return result(() => ({ settings: normalizePublishingSettings(JSON.parse(serialized)) }));
  },
  normalize(serialized) {
    return result(() => ({ project: normalizeProject(JSON.parse(serialized), sanitize) }));
  },
  compile(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      const project = normalizeProject(input.project, sanitize);
      return { project, files: renderSite(project, input.catalog, {
        publicPathPrefix: input.publicPathPrefix,
      }), referencedMedia: referencedMedia(project, window) };
    });
  },
};
