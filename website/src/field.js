import "./field.css";
import { figureDetails, richEditor, request } from "./editor.js";
import { safeLinkHref } from "./rich-text.js";

let value = "";
let configuration = "";
let nativeMediaDetails = false;
let nativeLinkDetails = false;
const native = Boolean(window.WebeasyNative || window.webkit?.messageHandlers?.webeasy);
const send = (method, payload) => {
  if (native) request(method, payload).catch((error) => { document.getElementById("status").textContent = error.message; });
};
window.WebeasyField = {
  validLink: safeLinkHref,
  setValue(html, options = {}) {
    const serialized = JSON.stringify(options);
    if (html === value && configuration === serialized && document.querySelector(".rich-editor")) return;
    value = html;
    configuration = serialized;
    const record = { id: "field", content: html };
    document.getElementById("field").replaceChildren(richEditor(record, "content", "Content", {
      project: { media: [] },
      paragraphsOnly: options.paragraphsOnly === true,
      getLinkProject: () => native ? request("pageLinks") : request("load").then((result) => result.project),
      getLinkDetails: (details) => nativeLinkDetails ? request("linkDetails", details) : undefined,
      getMediaDetails: (kind) => nativeMediaDetails ? request("mediaDetails", { kind }) : figureDetails(kind, options),
      onChange(html) { value = html; send("change", { content: html }); },
    }));
  },
};
window.WebeasyField.setValue("");
const viewport = () => {
  document.documentElement.style.setProperty("--editor-visible-top", `${window.visualViewport?.offsetTop ?? 0}px`);
  document.documentElement.style.setProperty("--editor-visible-height", `${window.visualViewport?.height ?? innerHeight}px`);
};
window.visualViewport?.addEventListener("resize", viewport);
window.visualViewport?.addEventListener("scroll", viewport);
viewport();
new ResizeObserver(() => {
  send("resize", { height: Math.ceil(document.body.getBoundingClientRect().height) + 8 });
}).observe(document.body);
if (native) request("ready", {}).then((capabilities) => {
  nativeMediaDetails = capabilities?.mediaDetails === true;
  nativeLinkDetails = capabilities?.linkDetails === true;
}).catch((error) => { document.getElementById("status").textContent = error.message; });
else window.WebeasyField.setValue("<p>Rich-text content field preview.</p>");
