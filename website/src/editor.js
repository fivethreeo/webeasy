import "./editor.css";
import { createRichTextSanitizer, safeLinkHref, wrapInlineParagraphs } from "./rich-text.js";
import { newId, normalizeProject, pageRoutes, sortedNews, renderSite, referencedMedia } from "./content-model.js";
import { editorIcon } from "./editor-icons.js";
import { setUiLanguage, uiText as t } from "./ui-text.js";

setUiLanguage(navigator.language);

const sanitize = createRichTextSanitizer(window);
const sanitizeShort = createRichTextSanitizer(window, true);
const $ = (id) => document.getElementById(id);
const native = Boolean(window.WebeasyNative?.request || window.webkit?.messageHandlers?.webeasy);
const requests = new Map();
window.WebeasyBridge = {
  receive(message) {
    const pending = requests.get(message.id);
    if (!pending) return;
    requests.delete(message.id);
    if (message.error) pending.reject(new Error(message.error));
    else pending.resolve(message.result);
  },
};
let project, catalog, selected, isPreview = false, dirty = false, selectionRange;
let revision = 0, saveQueue = Promise.resolve();
export const request = (method, payload = {}) => {
  if (!native) {
    if (method === "load") return Promise.all([
      fetch("content.json").then((response) => response.json()),
      fetch("catalog.json").then((response) => response.json()),
    ]).then(([project, catalog]) => ({ project, catalog }));
    if (method === "save") return Promise.resolve(null);
    return Promise.reject(new Error("Media import and website export require the mobile app."));
  }
  const id = newId();
  return new Promise((resolve, reject) => {
    requests.set(id, { resolve, reject });
    const message = JSON.stringify({ id, method, payload });
    if (window.WebeasyNative) window.WebeasyNative.request(message);
    else window.webkit.messageHandlers.webeasy.postMessage(message);
  });
};
const status = (message) => { $("status").textContent = t(message); };
const changed = () => { revision += 1; dirty = true; status("Unsaved changes."); };
const run = (action) => async (event) => {
  event?.preventDefault();
  try { await action(); } catch (error) { status(error.message); }
};
function element(tag, className, text) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}
function button(text, action) {
  const result = element("button", "", text);
  result.type = "button";
  result.addEventListener("click", run(action));
  return result;
}
function input(record, key, labelText, type = "text") {
  const label = element("label", "stack", t(labelText));
  const field = document.createElement("input");
  field.type = type;
  field.value = record[key] ?? "";
  field.required = key !== "heading" || record.asides !== undefined;
  field.addEventListener("input", () => { record[key] = field.value; changed(); refreshLabels(); });
  label.append(field);
  return label;
}
function rememberSelection(field) {
  const selection = window.getSelection();
  if (selection?.rangeCount && field.contains(selection.anchorNode) && field.contains(selection.focusNode)) {
    selectionRange = selection.getRangeAt(0).cloneRange();
  }
}
document.addEventListener("selectionchange", () => {
  const anchor = window.getSelection()?.anchorNode;
  const parent = anchor?.nodeType === 1 ? anchor : anchor?.parentElement;
  parent?.closest(".rich-editor")?.dispatchEvent(new Event("editor-selection"));
});
function restoreSelection(field, savedRange = selectionRange) {
  field.focus();
  const range = savedRange && field.contains(savedRange.commonAncestorContainer)
    ? savedRange : document.createRange();
  if (range !== savedRange) { range.selectNodeContents(field); range.collapse(false); }
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
  return range;
}
export function figureDetails(kind, options = {}) {
  const dialog = $("media-details");
  $("media-basename").value = "";
  $("media-alt").value = "";
  $("media-alt").parentElement.hidden = kind !== "image";
  $("image-settings").hidden = kind !== "image";
  for (const key of ["width", "quality"]) {
    const field = $(`image-${key}`), preset = $(`image-${key}-preset`);
    field.value = kind === "image" && options.simple ? (key === "width" ? "400" : "85") : "";
    preset.value = [...preset.options].some((option) => option.value === field.value) ? field.value : "custom";
    field.disabled = preset.disabled = kind !== "image";
    preset.onchange = () => {
      if (preset.value === "custom") field.focus();
      else field.value = preset.value;
    };
    field.oninput = () => {
      preset.value = [...preset.options].some((option) => option.value === field.value) ? field.value : "custom";
    };
  }
  $("media-caption").value = "";
  dialog.returnValue = "";
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => resolve(dialog.returnValue === "insert"
      ? {
        alt: $("media-alt").value, caption: $("media-caption").value, basename: $("media-basename").value.trim(),
        imageOptions: kind === "image" ? Object.fromEntries(
          [["width", $("image-width").value], ["quality", $("image-quality").value]]
            .filter(([, value]) => value !== "").map(([key, value]) => [key, Number(value)]),
        ) : {},
      } : null), { once: true });
    dialog.showModal();
  });
}
function linkDetails({ pages: choices, href, text: initialText, editing, textReadOnly }) {
  const dialog = document.createElement("dialog");
  const form = element("form", "stack");
  form.method = "dialog";
  form.append(element("h2", "", t(editing ? "Edit link" : "Insert link")));
  const pageLabel = element("label", "stack", t("Page or subpage"));
  const pages = document.createElement("select");
  pages.dataset.userContent = "";
  const custom = element("option", "", t("Enter a URL"));
  custom.value = "";
  pages.append(custom);
  for (const choice of choices) {
    const option = element("option", "", choice.title);
    option.value = choice.href;
    pages.append(option);
  }
  pageLabel.append(pages);
  const urlLabel = element("label", "stack", t("Link URL"));
  const url = document.createElement("input");
  url.type = "text";
  url.inputMode = "url";
  url.required = true;
  url.value = href;
  url.placeholder = "https://example.com";
  urlLabel.append(url);
  const textLabel = element("label", "stack", t("Link text"));
  const text = document.createElement("input");
  text.type = "text";
  text.value = initialText;
  text.readOnly = textReadOnly;
  textLabel.append(text);
  if (url.value && [...pages.options].some((option) => option.value === url.value)) pages.value = url.value;
  const validate = () => url.setCustomValidity(url.value && !safeLinkHref(url.value.trim())
    ? t("Use a web, email, telephone, or local page URL.") : "");
  pages.addEventListener("change", () => {
    if (pages.value) {
      url.value = pages.value;
      if (!text.value) text.value = pages.selectedOptions[0].textContent;
    }
    validate();
  });
  url.addEventListener("input", () => { pages.value = ""; validate(); });
  const actions = element("div", "cluster");
  const insert = element("button", "", t(editing ? "Save link" : "Insert link"));
  insert.type = "submit";
  insert.value = "insert";
  actions.append(insert, button(t("Cancel"), () => dialog.close("cancel")));
  if (editing) actions.append(button(t("Remove link"), () => dialog.close("remove")));
  form.append(pageLabel, urlLabel, textLabel, actions);
  form.addEventListener("submit", (event) => {
    validate();
    if (!form.checkValidity()) { event.preventDefault(); form.reportValidity(); }
  });
  dialog.append(form);
  document.body.append(dialog);
  validate();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => {
      resolve(dialog.returnValue === "remove" ? { remove: true } : dialog.returnValue === "insert" ? {
        href: url.value.trim(), text: textReadOnly ? initialText : text.value || url.value.trim(),
      } : null);
      dialog.remove();
    }, { once: true });
    dialog.showModal();
  });
}
export function richEditor(record, key, labelText, options = {}) {
  const short = options.paragraphsOnly === true || key === "shortContent";
  const sanitizeContent = short ? sanitizeShort : sanitize;
  const contentProject = options.project ?? project;
  const onChange = options.onChange ?? changed;
  const wrapper = element("div", "stack");
  const label = element("h3", "", t(labelText));
  label.id = `label-${record.id}-${key}`;
  const toolbar = element("div", "cluster formatting");
  toolbar.setAttribute("role", "group");
  toolbar.setAttribute("aria-label", t(`${labelText} formatting`));
  const field = element("div", "box stack rich-editor");
  field.contentEditable = "true";
  field.setAttribute("role", "textbox");
  field.setAttribute("aria-multiline", "true");
  field.setAttribute("aria-labelledby", label.id);
  field.innerHTML = sanitizeContent(record[key]) || "<p></p>";
  let activeFigure = null;
  const selectFigure = (figure) => {
    activeFigure?.removeAttribute("data-editor-selected");
    activeFigure = figure;
    activeFigure?.setAttribute("data-editor-selected", "");
  };
  const marks = document.createDocumentFragment();
  const paragraphs = document.createDocumentFragment();
  const mediaActions = document.createDocumentFragment();
  const markControls = [];
  let pendingMark = null;
  let formatting = false;
  let typingGroup = null;
  let lastInput = 0;
  const editorHTML = () => {
    const copy = field.cloneNode(true);
    copy.querySelectorAll("[data-editor-selected]").forEach((figure) => figure.removeAttribute("data-editor-selected"));
    return copy.innerHTML;
  };
  const caret = () => {
    const selection = window.getSelection();
    if (!selection?.rangeCount || !field.contains(selection.anchorNode) || !field.contains(selection.focusNode)) return null;
    const boundary = (node, offset) => {
      const path = [];
      while (node !== field) {
        path.unshift([...node.parentNode.childNodes].indexOf(node));
        node = node.parentNode;
      }
      return { path, offset };
    };
    return [boundary(selection.anchorNode, selection.anchorOffset), boundary(selection.focusNode, selection.focusOffset)];
  };
  const history = [{ html: editorHTML(), caret: null }];
  let historyIndex = 0;
  const recordHistory = (group) => {
    field.normalize();
    const html = editorHTML();
    if (html !== history[historyIndex].html) {
      const combine = group && group === typingGroup && Date.now() - lastInput < 1000
        && historyIndex > 0 && historyIndex === history.length - 1;
      history.splice(historyIndex + 1);
      if (combine) history[historyIndex] = { html, caret: caret() };
      else { history.push({ html, caret: caret() }); historyIndex += 1; }
    } else history[historyIndex].caret = caret();
    typingGroup = group;
    lastInput = Date.now();
    undo.disabled = historyIndex === 0;
    redo.disabled = historyIndex === history.length - 1;
  };
  const travelHistory = (direction) => {
    const next = historyIndex + direction;
    if (next < 0 || next >= history.length) return;
    historyIndex = next;
    typingGroup = null;
    pendingMark = null;
    emphasizeTyping = false;
    selectFigure(null);
    field.innerHTML = history[historyIndex].html;
    restoreSelection(field, null);
    const position = history[historyIndex].caret;
    if (position) {
      const boundary = ({ path, offset }) => {
        const node = path.reduce((parent, index) => parent.childNodes[index], field);
        return [node, Math.min(offset, node.nodeType === 3 ? node.length : node.childNodes.length)];
      };
      window.getSelection().setBaseAndExtent(...boundary(position[0]), ...boundary(position[1]));
    }
    rememberSelection(field);
    update();
    refreshMarks();
  };
  const historyControl = (name, direction) => {
    const control = button(t(name), () => travelHistory(direction));
    control.setAttribute("aria-label", t(name));
    control.title = t(name);
    editorIcon(control, name.toLowerCase());
    control.disabled = true;
    control.addEventListener("pointerdown", (event) => event.preventDefault());
    return control;
  };
  const undo = historyControl("Undo", -1);
  const redo = historyControl("Redo", 1);
  const refreshMarks = () => {
    const selection = window.getSelection();
    if (!selection?.rangeCount || !field.contains(selection.anchorNode)) return;
    rememberSelection(field);
    if (editorHTML() === history[historyIndex].html) {
      const position = caret();
      if (JSON.stringify(position) !== JSON.stringify(history[historyIndex].caret)) typingGroup = null;
      history[historyIndex].caret = position;
    }
    const range = selection.getRangeAt(0);
    if (pendingMark && (!range.collapsed
      || range.compareBoundaryPoints(Range.START_TO_START, pendingMark.range) !== 0)) pendingMark = null;
    const selectedText = [];
    if (!range.collapsed) {
      const walker = document.createTreeWalker(field, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        const text = walker.currentNode;
        if (text.length && range.intersectsNode(text)
          && !(range.startContainer === text && range.startOffset === text.length)
          && !(range.endContainer === text && range.endOffset === 0)) selectedText.push(text);
      }
    }
    const hasMark = (selector) => {
      if (range.collapsed) {
        const node = range.startContainer;
        return Boolean((node.nodeType === 1 ? node : node.parentElement)?.closest(selector));
      }
      return selectedText.length > 0 && selectedText.every((text) => text.parentElement.closest(selector));
    };
    const node = range.commonAncestorContainer;
    let figure = (node.nodeType === 1 ? node : node.parentElement)?.closest("figure");
    if (!figure && range.startContainer === range.endContainer && range.endOffset === range.startOffset + 1) {
      const child = range.startContainer.childNodes[range.startOffset];
      if (child?.nodeName === "FIGURE") figure = child;
    }
    selectFigure(figure && field.contains(figure) ? figure : null);
    for (const [control, command, selector, name] of markControls) {
      const active = selector ? pendingMark?.command === command
        ? pendingMark.name === name && pendingMark.active : hasMark(selector)
        : document.queryCommandState(command);
      control.setAttribute("aria-pressed", String(active));
    }
    link.setAttribute("aria-pressed", String(hasMark("a")));
  };
  let emphasizeTyping = false;
  const semanticMark = (source, tag) => {
    const selection = window.getSelection();
    const anchor = selection.anchorNode, focus = selection.focusNode;
    const anchorOffset = selection.anchorOffset, focusOffset = selection.focusOffset;
    const replacements = new Map();
    field.querySelectorAll(source).forEach((original) => {
      const replacement = document.createElement(tag);
      replacement.append(...original.childNodes);
      original.replaceWith(replacement);
      replacements.set(original, replacement);
    });
    if (replacements.size && anchor && focus) selection.setBaseAndExtent(replacements.get(anchor) ?? anchor, anchorOffset,
      replacements.get(focus) ?? focus, focusOffset);
  };
  const normalizeParagraphs = () => {
    const selection = window.getSelection();
    const boundary = (node, offset) => {
      if (node !== field) return () => [node, offset];
      const after = offset === field.childNodes.length;
      const child = after ? field.lastChild : field.childNodes[offset];
      return () => child
        ? [child.parentNode, [...child.parentNode.childNodes].indexOf(child) + Number(after)]
        : [field.firstElementChild, 0];
    };
    const anchor = selection.anchorNode, focus = selection.focusNode;
    const anchorBoundary = boundary(anchor, selection.anchorOffset);
    const focusBoundary = boundary(focus, selection.focusOffset);
    let changed = wrapInlineParagraphs(field);
    if (!field.childNodes.length) {
      field.append(document.createElement("p"));
      changed = true;
    }
    if (changed && anchor && focus) selection.setBaseAndExtent(...anchorBoundary(), ...focusBoundary());
  };
  const update = (group = null) => {
    semanticMark("b", "strong");
    normalizeParagraphs();
    record[key] = sanitizeContent(field.innerHTML);
    onChange(record[key]);
    recordHistory(group);
  };
  const format = (command, value, mark) => {
    restoreSelection(field);
    formatting = true;
    try {
      document.execCommand("styleWithCSS", false, false);
      document.execCommand(command, false, value);
      if (command === "italic") emphasizeTyping = mark === "Emphasis" && document.queryCommandState("italic");
      if (emphasizeTyping) semanticMark("i", "em");
    } finally { formatting = false; }
    const range = window.getSelection().getRangeAt(0);
    pendingMark = range.collapsed && ["italic", "underline"].includes(command)
      ? { name: mark, command, active: document.queryCommandState(command), range: range.cloneRange() } : null;
    update(); refreshMarks();
  };
  const block = document.createElement("select");
  block.setAttribute("aria-label", t("Paragraph or heading"));
  for (const [value, text] of [["p", "Paragraph"], ["h2", "Heading 2"], ["h3", "Heading 3"], ["h4", "Heading 4"], ["h5", "Heading 5"], ["h6", "Heading 6"]]) {
    const option = element("option", "", t(text)); option.value = value; block.append(option);
  }
  block.addEventListener("pointerdown", () => rememberSelection(field));
  block.addEventListener("change", () => format("formatBlock", block.value));
  for (const [text, caption, command, value] of [
    ["Bold", "B", "bold"], ["Italic", "I", "italic"], ["Strike", "S", "strikeThrough"], ["Underline", "U", "underline"],
    ["Emphasis", "Em", "italic"], ["Bullets", "Bullets", "insertUnorderedList"],
    ["Numbers", "Numbers", "insertOrderedList"], ["Quote", "Quote", "formatBlock", "blockquote"],
  ]) {
    if (short && ["insertUnorderedList", "insertOrderedList", "formatBlock"].includes(command)) continue;
    const control = button(caption, () => format(command, value, text));
    control.setAttribute("aria-label", t(text));
    control.title = t(text);
    editorIcon(control, {
      Bold: "strong", Italic: "italic", Emphasis: "emphasis", Strike: "strike", Underline: "underline",
      Bullets: "bulletList", Numbers: "orderedLint", Quote: "quote",
    }[text]);
    if (["bold", "italic", "strikeThrough", "underline"].includes(command)) {
      control.setAttribute("aria-pressed", "false");
      markControls.push([control, command, { Italic: "i", Emphasis: "em", Underline: "u" }[text], text]);
    }
    control.addEventListener("pointerdown", (event) => { rememberSelection(field); event.preventDefault(); });
    (["insertUnorderedList", "insertOrderedList", "formatBlock"].includes(command) ? paragraphs : marks).append(control);
  }
  if (!short) paragraphs.prepend(block);
  for (const kind of short ? [] : ["image", "video"]) {
    const control = button(kind === "image" ? "Image" : "Video", async () => {
      const mediaRange = restoreSelection(field).cloneRange();
      control.disabled = true;
      try {
        const details = await (options.getMediaDetails ? options.getMediaDetails(kind) : figureDetails(kind));
        if (!details) { status("Figure cancelled."); restoreSelection(field, mediaRange); return; }
        status("Choose media on your phone...");
        const media = await request("pickMedia", { kind, basename: details.basename, ...details.imageOptions });
        if (!media) { status("Media selection cancelled."); return; }
        if (!contentProject.media.some((item) => item.id === media.id)) contentProject.media.push(media);
        const figure = element("figure", "stack");
        if (kind === "image") {
          const img = document.createElement("img");
          img.setAttribute("src", `media/${media.filename}`);
          img.setAttribute("alt", details.alt);
          figure.append(img);
        } else {
          const video = document.createElement("video");
          video.setAttribute("controls", "");
          video.setAttribute("playsinline", "");
          video.setAttribute("preload", "metadata");
          const source = document.createElement("source");
          source.setAttribute("src", `media/${media.filename}`);
          source.setAttribute("type", media.mimeType);
          video.append(source);
          figure.append(video);
        }
        if (details.caption) figure.append(element("figcaption", "", details.caption));
        const range = restoreSelection(field, mediaRange);
        // Insert between blocks rather than creating invalid figures inside paragraphs.
        let block = range.startContainer.nodeType === 1 ? range.startContainer : range.startContainer.parentElement;
        while (block?.parentElement && block.parentElement !== field) block = block.parentElement;
        if (block && block !== field && block.parentElement === field) block.after(figure);
        else field.append(figure);
        const paragraph = element("p", "", "");
        paragraph.append(document.createElement("br"));
        figure.after(paragraph);
        update();
      } finally { control.disabled = false; }
    });
    control.setAttribute("aria-label", t(kind === "image" ? "Phone image" : "Phone video"));
    control.title = t(kind === "image" ? "Insert image" : "Insert video");
    editorIcon(control, kind);
    control.disabled = !native;
    control.addEventListener("pointerdown", () => rememberSelection(field));
    mediaActions.append(control);
  }
  const selectedLink = (range = restoreSelection(field)) => {
    const node = range.commonAncestorContainer;
    if (range.startContainer === range.endContainer && range.endOffset === range.startOffset + 1) {
      const child = range.startContainer.childNodes[range.startOffset];
      if (child?.nodeName === "A") return child;
    }
    return (node.nodeType === 1 ? node : node.parentElement)?.closest("a");
  };
  const link = button(t("Link"), async () => {
    const linkRange = restoreSelection(field).cloneRange();
    const current = selectedLink(linkRange);
    const selectedText = linkRange.toString();
    const linkProject = options.getLinkProject ? await options.getLinkProject() : contentProject;
    const configuration = {
      href: (current?.getAttribute("href") ?? "").replace(/^(?:\.\/)?index\.html$/, "/"),
      text: current?.textContent ?? selectedText,
      editing: Boolean(current),
      textReadOnly: Boolean(selectedText) && !current,
      pages: pageRoutes(linkProject.pages ?? []).map((entry) => ({
        title: [...entry.ancestors, entry.record].map((page) => page.menuTitle || page.title).join(" / "),
        href: entry.record.id === (linkProject.homePageId ?? linkProject.pages?.[0]?.id) ? "/" : entry.route.slice(1),
      })),
    };
    const details = await (options.getLinkDetails?.(configuration) ?? linkDetails(configuration));
    if (!details) { restoreSelection(field, linkRange); return; }
    if (!details.remove && !safeLinkHref(details.href)) {
      restoreSelection(field, linkRange);
      status("Use a web, email, telephone, or local page URL.");
      return;
    }
    const range = restoreSelection(field, linkRange);
    if (details.remove) {
      if (current && field.contains(current)) current.replaceWith(...current.childNodes);
    } else if (current && field.contains(current)) {
      current.setAttribute("href", details.href);
      if (current.textContent !== details.text) current.textContent = details.text;
    } else if (!range.collapsed && range.toString() === details.text) {
      document.execCommand("createLink", false, details.href);
    } else {
      const anchor = element("a", "", details.text);
      anchor.setAttribute("href", details.href);
      document.execCommand("insertHTML", false, anchor.outerHTML);
    }
    update(); refreshMarks();
  });
  link.setAttribute("aria-label", t("Link"));
  link.setAttribute("aria-pressed", "false");
  link.title = t("Insert or edit link");
  editorIcon(link, "link");
  link.addEventListener("pointerdown", (event) => { rememberSelection(field); event.preventDefault(); });
  marks.append(link);
  toolbar.append(undo, redo, marks, paragraphs, mediaActions);
  const removeSelectedFigure = (event) => {
    const selection = window.getSelection();
    if (!activeFigure || !field.contains(activeFigure) || !selection?.rangeCount || selection.isCollapsed) return;
    const range = selection.getRangeAt(0);
    const selected = document.createRange();
    const fullySelected = [activeFigure, ...activeFigure.querySelectorAll(":scope > img, :scope > video")].some((node) => {
      selected.selectNode(node);
      return range.compareBoundaryPoints(Range.START_TO_START, selected) === 0
        && range.compareBoundaryPoints(Range.END_TO_END, selected) === 0;
    });
    if (!fullySelected) return;
    event.preventDefault();
    const figure = activeFigure;
    const caret = document.createRange();
    caret.setStartBefore(figure); caret.collapse(true);
    selectFigure(null);
    figure.remove();
    restoreSelection(field, caret);
    rememberSelection(field);
    update(); refreshMarks();
  };
  field.addEventListener("focus", () => document.execCommand("defaultParagraphSeparator", false, "p"));
  field.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
      event.preventDefault();
      travelHistory(event.shiftKey ? 1 : -1);
    } else if (event.ctrlKey && event.key.toLowerCase() === "y") {
      event.preventDefault();
      travelHistory(1);
    }
    if (["Backspace", "Delete"].includes(event.key)) removeSelectedFigure(event);
  });
  field.addEventListener("beforeinput", (event) => {
    if (["historyUndo", "historyRedo"].includes(event.inputType)) {
      event.preventDefault();
      travelHistory(event.inputType === "historyUndo" ? -1 : 1);
      return;
    }
    if (editorHTML() === history[historyIndex].html) history[historyIndex].caret = caret();
    if (["deleteContentBackward", "deleteContentForward"].includes(event.inputType)) removeSelectedFigure(event);
    if (short && ["insertOrderedList", "insertUnorderedList"].includes(event.inputType)) event.preventDefault();
  });
  field.addEventListener("input", (event) => {
    if (formatting) return;
    if (emphasizeTyping) semanticMark("i", "em");
    update(["insertText", "deleteContentBackward", "deleteContentForward"].includes(event.inputType) ? event.inputType : null);
    refreshMarks();
  });
  field.addEventListener("editor-selection", refreshMarks);
  field.addEventListener("keyup", refreshMarks);
  field.addEventListener("blur", () => { record[key] = sanitizeContent(field.innerHTML); });
  const selectMedia = (event) => {
    const figure = event.target.closest("figure");
    if (figure && !event.target.closest("figcaption")) {
      const range = document.createRange();
      range.selectNode(figure);
      restoreSelection(field, range);
    }
    refreshMarks();
  };
  // Native video controls can consume the subsequent click.
  field.addEventListener("pointerup", (event) => { emphasizeTyping = false; selectMedia(event); }, { capture: true });
  field.addEventListener("click", (event) => {
    if (event.target.closest("a")) event.preventDefault();
    selectMedia(event);
  });
  field.addEventListener("paste", (event) => {
    event.preventDefault();
    const html = event.clipboardData.getData("text/html");
    if (html) document.execCommand("insertHTML", false, sanitizeContent(html));
    else document.execCommand("insertText", false, event.clipboardData.getData("text/plain"));
    update();
  });
  field.addEventListener("drop", (event) => {
    if (!short) return;
    event.preventDefault();
    const html = event.dataTransfer.getData("text/html");
    if (html) document.execCommand("insertHTML", false, sanitizeContent(html));
    else document.execCommand("insertText", false, event.dataTransfer.getData("text/plain"));
    update();
  });
  wrapper.append(label, toolbar, field);
  return wrapper;
}
function collection(owner, key, title, create, renderRecord) {
  const wrapper = element("section", "stack");
  const header = element("div", "cluster");
  header.append(element("h3", "", title), button(`Add ${key === "sections" ? "section" : "aside"}`, () => {
    owner[key].push(create()); changed(); renderEditor();
  }));
  wrapper.append(header);
  owner[key].forEach((record, index) => {
    const card = element("section", "stack box");
    const actions = element("div", "cluster");
    const move = (offset) => {
      const target = index + offset;
      if (target < 0 || target >= owner[key].length) return;
      [owner[key][index], owner[key][target]] = [owner[key][target], owner[key][index]];
      changed(); renderEditor();
    };
    actions.append(button(t("Move up"), () => move(-1)), button(t("Move down"), () => move(1)),
      button(t("Remove"), () => { owner[key].splice(index, 1); changed(); renderEditor(); }));
    card.append(...renderRecord(record), actions);
    wrapper.append(card);
  });
  return wrapper;
}
const asideList = (owner) => collection(owner, "asides", "Asides",
  () => ({ id: newId(), heading: "", content: "<p></p>" }),
  (aside) => [input(aside, "heading", "Heading (optional)"), richEditor(aside, "content", "Aside content")]);
const sectionList = (owner) => collection(owner, "sections", "Sections",
  () => ({ id: newId(), heading: "New section", content: "<p></p>", asides: [] }),
  (section) => [input(section, "heading", "Section heading"), richEditor(section, "content", "Section content"), asideList(section)]);

function findSelected() {
  return pageRoutes(project.pages).map((entry) => ({ ...entry, kind: "page" }))
    .concat(project.news.map((record) => ({ record, route: `/news/${record.date}/${record.slug}/`, kind: "news" })))
    .find((entry) => entry.record.id === selected);
}
function refreshLabels() {
  const pages = pageRoutes(project.pages);
  const labels = new Map([
    ...pages.map((entry) => [entry.record.id, `${"— ".repeat(entry.ancestors.length)}${entry.record.menuTitle || entry.record.title || "Untitled"}`]),
    ...project.news.map((record) => [record.id, `${record.date} · ${record.title || "Untitled"}`]),
  ]);
  document.querySelectorAll(".record-list button").forEach((control) => {
    control.textContent = labels.get(control.dataset.recordId) ?? "";
  });
  const notice = $("selected-route");
  if (notice) notice.textContent = `URL: ${findSelected()?.route ?? ""}`;
}
function renderLists() {
  const add = (record, list, label) => {
    const li = document.createElement("li");
    const control = button(label, () => {
      selected = record.id; isPreview = false; selectionRange = null; renderEditor(); renderLists();
      if (!window.matchMedia("(min-width: 60rem)").matches) $("content-navigation").open = false;
    });
    control.dataset.recordId = record.id;
    control.setAttribute("aria-current", String(selected === record.id));
    li.append(control); list.append(li);
  };
  $("pages").replaceChildren();
  for (const entry of pageRoutes(project.pages)) {
    add(entry.record, $("pages"), `${"— ".repeat(entry.ancestors.length)}${entry.record.menuTitle || entry.record.title}`);
  }
  $("news").replaceChildren();
  for (const record of sortedNews(project.news)) add(record, $("news"), `${record.date} · ${record.title}`);
  $("pages").classList.add("record-list"); $("news").classList.add("record-list");
  $("add-subpage").disabled = findSelected()?.kind !== "page";
}
function renderEditor() {
  const form = $("editor-form");
  form.replaceChildren();
  form.hidden = isPreview;
  $("preview").hidden = !isPreview;
  $("preview-toggle").textContent = isPreview ? "Edit" : "Preview";
  const entry = findSelected();
  if (isPreview) {
    const normalized = normalizeProject(project, sanitize, sanitizeShort);
    const path = entry ? `${entry.route.slice(1)}index.html` : "news/index.html";
    const html = renderSite(normalized, catalog).find((file) => file.path === path)?.html;
    $("preview").innerHTML = new DOMParser().parseFromString(html, "text/html").body.innerHTML;
    // The editor lives at the website root; its preview uses root-relative media/menu paths.
    $("preview").querySelectorAll('a[href]:not([href^="#"])').forEach((link) => link.addEventListener("click", run(() => {
      const href = link.getAttribute("href");
      const path = href.split(/[?#]/)[0].replace(/^\//, "").replace(/index\.html$/, "");
      if (path === "" || path === "./") {
        selected = normalized.homePageId; renderEditor(); renderLists(); return;
      }
      const target = pageRoutes(project.pages).find((item) => item.route.slice(1) === path)
        ?? project.news.map((record) => ({ record, route: `news/${record.date}/${record.slug}/` })).find((item) => item.route === path);
      if (!target && path !== "news/") { window.open(link.href, "_blank", "noopener,noreferrer"); return; }
      selected = target?.record.id; renderEditor(); renderLists();
    })));
    return;
  }
  if (!entry) { form.append(element("p", "", "Add a page or news item to start editing.")); return; }
  const { record, kind } = entry;
  form.append(element("h2", "", kind === "page" ? "Edit page" : "Edit news"),
    input(record, "title", "Title"));
  if (kind === "page") {
    form.append(input(record, "menuTitle", "Menu title"));
    const label = element("label", "stack", "Page layout");
    const layout = document.createElement("select");
    layout.setAttribute("aria-label", "Page layout");
    for (const entry of catalog.templates.filter((item) => item.pageLayout === true)) {
      const option = element("option", "", entry.title); option.value = entry.id; layout.append(option);
    }
    layout.value = record.templateId ?? "page";
    layout.addEventListener("change", () => { record.templateId = layout.value; changed(); });
    label.append(layout); form.append(label);
  }
  const routeNotice = element("p", "", `URL: ${entry.route}`);
  routeNotice.id = "selected-route";
  form.append(input(record, "slug", "Slug"), input(record, "date", "Date", "date"),
    routeNotice,
    richEditor(record, kind === "page" ? "content" : "shortContent", kind === "page" ? "Content" : "Short content"));
  if (kind === "news") form.append(richEditor(record, "content", "Long content"));
  form.append(asideList(record), sectionList(record));
  const actions = element("div", "cluster");
  const siblings = kind === "news" ? project.news : entry.ancestors.at(-1)?.children ?? project.pages;
  const move = (offset) => {
    const index = siblings.indexOf(record), next = index + offset;
    if (next < 0 || next >= siblings.length) return;
    [siblings[index], siblings[next]] = [siblings[next], siblings[index]];
    changed(); renderLists();
  };
  if (kind === "page") {
    const home = button(t("Use as home page"), () => {
      project.homePageId = record.id; changed(); renderEditor();
    });
    if (project.homePageId === record.id) { home.textContent = "Current home page"; home.disabled = true; }
    actions.append(home, button(t("Move page up"), () => move(-1)), button(t("Move page down"), () => move(1)));
  }
  actions.append(button(t("Delete this item and its owned content"), () => {
    const dialog = document.createElement("dialog");
    dialog.append(element("p", "", "Delete this item, its subpages, sections, and asides?"));
    dialog.append(button(t("Delete"), () => {
      siblings.splice(siblings.indexOf(record), 1);
      if (!pageRoutes(project.pages).some((entry) => entry.record.id === project.homePageId)) {
        project.homePageId = project.pages[0]?.id ?? null;
      }
      selected = project.pages[0]?.id ?? project.news[0]?.id;
      dialog.close(); dialog.remove(); changed(); renderLists(); renderEditor();
    }), button(t("Cancel"), () => { dialog.close(); dialog.remove(); }));
    document.body.append(dialog); dialog.showModal();
  }));
  form.append(actions);
}
function createContent(kind, parent) {
  const today = new Date();
  const date = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const collection = kind === "news" ? project.news : parent?.children ?? project.pages;
  let index = collection.length + 1, slug;
  do { slug = `${kind}-${index++}`; } while (collection.some((record) => record.slug === slug));
  const record = { id: newId(), title: kind === "news" ? "New story" : "New page", slug, date, sections: [], asides: [] };
  if (kind === "news") Object.assign(record, { shortContent: "<p></p>", content: "<p></p>" });
  else Object.assign(record, { menuTitle: "New page", content: "<p></p>", children: [] });
  collection.push(record);
  selected = record.id; isPreview = false; changed(); renderLists(); renderEditor();
}
async function save() {
  if (!$("editor-form").reportValidity()) throw new Error("Complete the required fields before saving.");
  const normalized = normalizeProject(project, sanitize, sanitizeShort);
  const savedRevision = revision;
  saveQueue = saveQueue.catch(() => {}).then(() => request("save", { project: normalized }));
  await saveQueue;
  dirty = revision !== savedRevision;
  status(dirty ? "More recent changes are still unsaved." : native ? "Saved locally to SQLite." : "Preview only: changes are not stored.");
  return normalized;
}
if ($("editor-form")) {
$("save").addEventListener("click", run(save));
$("editor-form").addEventListener("submit", run(save));
document.querySelectorAll(".editor-shell > header a").forEach((link) => {
  link.addEventListener("click", run(async () => {
    if (dirty) await save();
    window.location.href = link.href;
  }));
});
$("export").addEventListener("click", run(async () => {
  $("export").disabled = true;
  try {
    const normalized = await save();
    status("Preparing website...");
    await request("export", { files: renderSite(normalized, catalog), referencedMedia: referencedMedia(normalized, window) });
    status(dirty ? "Website export ready. More recent changes are still unsaved." : "Website ready to save or share. The SQLite database is not included.");
  } finally { $("export").disabled = !native; }
}));
$("add-page").addEventListener("click", run(() => createContent("page")));
$("add-subpage").addEventListener("click", run(() => createContent("page", findSelected()?.record)));
$("add-news").addEventListener("click", run(() => createContent("news")));
$("preview-toggle").addEventListener("click", run(() => {
  if (!isPreview) normalizeProject(project, sanitize, sanitizeShort);
  isPreview = !isPreview; renderEditor();
}));
$("preview-news").addEventListener("click", run(() => {
  normalizeProject(project, sanitize, sanitizeShort);
  selected = undefined; isPreview = true; renderEditor(); renderLists();
}));
window.addEventListener("beforeunload", (event) => {
  if (dirty) { event.preventDefault(); event.returnValue = ""; }
});
run(async () => {
  const loaded = await request("load");
  project = normalizeProject(loaded.project, sanitize, sanitizeShort); catalog = loaded.catalog;
  selected = project.pages[0]?.id ?? project.news[0]?.id;
  $("storage-mode").textContent = native
    ? "Local SQLite project. Save edits before closing the app."
    : "Browser preview only: edits are not stored. The mobile apps use local SQLite.";
  $("export").disabled = !native;
  $("content-navigation").open = window.matchMedia("(min-width: 60rem)").matches;
  renderLists(); renderEditor(); status("Ready.");
})();
}
