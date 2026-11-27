import { createRichTextSanitizer } from "./rich-text.js";
import { referencedMedia } from "./content-model.js";
import { readConnectionQR } from "./connection.mjs";
import { normalizePublishingSettings } from "./publishing-settings.js";
import { projectChanges } from "./content-changes.js";
import { templateSources, loadTemplateSources, templatePreview } from "./template-sources.js";
import { normalizeRequest, prepareRequest, compileRequest } from "./c-renderer.js";

const sanitizeShort = createRichTextSanitizer(window, true);
function result(action) {
  try { return JSON.stringify(action()); }
  catch (error) { return JSON.stringify({ error: error.message }); }
}
window.WebeasyGenerator = {
  previewTemplate(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      const html = templatePreview(input.catalog, input.id);
      return { files: [{ path: "index.html", html }],
        referencedMedia: referencedMedia({ content: html, media: input.media ?? [] }, window) };
    });
  },
  templates(serialized) {
    return result(() => ({ sources: templateSources(JSON.parse(serialized)) }));
  },
  loadTemplates(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      const catalog = loadTemplateSources(input.catalog, input.sources);
      compileRequest({ project: input.project, catalog });
      return { catalog };
    });
  },
  changes(serialized) {
    return result(() => {
      const input = JSON.parse(serialized);
      const newsText = (project) => ({ ...project, news: (project.news ?? []).map((item) => ({
        ...item, content: item.content ?? item.shortContent ?? "", shortContent: sanitizeShort(item.shortContent ?? ""),
      })) });
      return { changes: projectChanges(newsText(input.before), newsText(input.after)) };
    });
  },
  readConnection(serialized) {
    return result(() => ({ connection: readConnectionQR(serialized) }));
  },
  readPublishingSettings(serialized) {
    return result(() => ({ settings: normalizePublishingSettings(JSON.parse(serialized)) }));
  },
  normalize(serialized) {
    return result(() => normalizeRequest(JSON.parse(serialized)));
  },
  prepare(serialized) {
    return result(() => {
      return prepareRequest(JSON.parse(serialized));
    });
  },
  compile(serialized) {
    return result(() => {
      return compileRequest(JSON.parse(serialized));
    });
  },
};
