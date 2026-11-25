import "./field.css";
import { richEditor, request } from "./editor.js";

let value = "";
const native = Boolean(window.WebeasyNative || window.webkit?.messageHandlers?.webeasy);
const send = (method, payload) => {
  if (native) request(method, payload).catch((error) => { document.getElementById("status").textContent = error.message; });
};
window.WebeasyField = {
  setValue(html) {
    if (html === value && document.querySelector(".rich-editor")) return;
    value = html;
    const record = { id: "field", content: html };
    document.getElementById("field").replaceChildren(richEditor(record, "content", "Content", {
      project: { media: [] },
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
if (native) send("ready", {});
else window.WebeasyField.setValue("<p>Rich-text content field preview.</p>");
