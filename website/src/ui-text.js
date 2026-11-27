import translations from "../../shared/ui-nb.json" with { type: "json" };

let language = "en";
const originalText = new WeakMap();
const originalAttributes = new WeakMap();

export function uiLanguage(value) {
  return /^(nb|no)(-|$)/i.test(value ?? "") ? "nb" : "en";
}

export function uiText(text, locale = language) {
  if (uiLanguage(locale) !== "nb") return text;
  if (Object.hasOwn(translations, text)) return translations[text];
  return text.replace(/^(\d+) pixels$/, "$1 piksler");
}

export function translateUi(root) {
  const document = root.ownerDocument ?? root;
  const ignored = ".rich-editor,[contenteditable],[data-user-content],#preview,#pages,#news,input,textarea,pre,code";
  const walker = document.createTreeWalker(root, 4);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.parentElement?.closest(ignored)) continue;
    const source = originalText.get(node) ?? node.nodeValue.trim();
    if (!Object.hasOwn(translations, source) && !/^\d+ pixels$/.test(source)) continue;
    if (!originalText.has(node)) originalText.set(node, source);
    const leading = node.nodeValue.match(/^\s*/)[0], trailing = node.nodeValue.match(/\s*$/)[0];
    node.nodeValue = leading + uiText(source) + trailing;
  }
  for (const element of root.querySelectorAll("[aria-label],[title]")) {
    if (element.closest(ignored)) continue;
    const sources = originalAttributes.get(element) ?? {};
    for (const name of ["aria-label", "title"]) {
      const value = sources[name] ?? element.getAttribute(name);
      if (value && Object.hasOwn(translations, value)) {
        sources[name] = value;
        element.setAttribute(name, uiText(value));
      }
    }
    originalAttributes.set(element, sources);
  }
}

export function setUiLanguage(locale, document = globalThis.document) {
  language = uiLanguage(locale);
  if (document) {
    document.documentElement.lang = language;
    translateUi(document);
  }
}
