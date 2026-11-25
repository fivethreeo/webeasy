import createDOMPurify from "dompurify";

export const richTextTags = [
  "h1", "h2", "h3", "h4", "h5", "h6", "p", "br", "b", "strong", "i", "em",
  "s", "strike", "del", "u", "ul", "ol", "li", "blockquote",
  "figure", "figcaption", "img", "video", "source", "a",
];
const attributes = {
  a: ["href", "title"],
  img: ["src", "alt", "width", "height", "loading"],
  video: ["src", "poster", "controls", "playsinline", "preload", "width", "height"],
  source: ["src", "type"],
};

export function safeLinkHref(value) {
  if (!value || /[\u0000-\u0020\u007f\\]/.test(value)) return false;
  try {
    const url = new URL(value, "https://local.invalid/");
    if (/^https?:\/\//i.test(value)) return Boolean(url.hostname);
    if (/^(mailto|tel):/i.test(value)) return value.split(":").slice(1).join(":").length > 0;
    if (value.startsWith("//") || value.split(/[?#]/)[0].includes(":")) return false;
    const path = decodeURIComponent(value.split(/[?#]/)[0]);
    return !path.split("/").some((part) => part === "." || part === "..");
  } catch { return false; }
}

export function localMediaPath(value) {
  return /^(assets|media)\/[a-zA-Z0-9][a-zA-Z0-9._/-]*$/.test(value) &&
    !value.split("/").some((part) => part === "." || part === "..");
}

export function createRichTextSanitizer(window) {
  const purifier = createDOMPurify(window);
  purifier.addHook("uponSanitizeAttribute", (node, data) => {
    const tag = node.tagName.toLowerCase();
    if (!attributes[tag]?.includes(data.attrName)) {
      data.keepAttr = false;
      return;
    }
    if (["src", "poster"].includes(data.attrName) && !localMediaPath(data.attrValue)) data.keepAttr = false;
    if (data.attrName === "href" && !safeLinkHref(data.attrValue)) data.keepAttr = false;
    if (["width", "height"].includes(data.attrName) && !/^[1-9][0-9]*$/.test(data.attrValue)) data.keepAttr = false;
    if (data.attrName === "preload" && !["none", "metadata", "auto"].includes(data.attrValue)) data.keepAttr = false;
    if (data.attrName === "loading" && !["lazy", "eager"].includes(data.attrValue)) data.keepAttr = false;
    if (data.attrName === "type" && !/^video\/(mp4|webm|quicktime|ogg)$/.test(data.attrValue)) data.keepAttr = false;
  });
  return (html) => {
    const fragment = purifier.sanitize(html, {
      ALLOWED_TAGS: richTextTags,
      ALLOWED_ATTR: [...new Set(Object.values(attributes).flat())],
      ALLOW_DATA_ATTR: false,
      ALLOW_ARIA_ATTR: false,
      RETURN_DOM_FRAGMENT: true,
    });
    for (const heading of fragment.querySelectorAll("h1")) {
      const replacement = window.document.createElement("h2");
      replacement.append(...heading.childNodes);
      heading.replaceWith(replacement);
    }
    for (const media of fragment.querySelectorAll("img,video")) {
      if (media.tagName === "IMG" && !media.hasAttribute("src")) {
        media.remove();
        continue;
      }
      if (!media.closest("figure")) {
        const figure = window.document.createElement("figure");
        media.replaceWith(figure);
        figure.append(media);
      }
      if (media.tagName === "IMG") {
        if (!media.hasAttribute("alt")) media.setAttribute("alt", "");
        if (!media.hasAttribute("loading")) media.setAttribute("loading", "lazy");
      } else {
        media.setAttribute("controls", "");
        media.setAttribute("playsinline", "");
        if (!media.hasAttribute("preload")) media.setAttribute("preload", "metadata");
      }
    }
    for (const source of fragment.querySelectorAll("source")) {
      if (source.parentElement?.tagName !== "VIDEO" || !source.hasAttribute("src")) source.remove();
    }
    for (const figure of fragment.querySelectorAll("figure")) figure.className = "stack";
    const container = window.document.createElement("div");
    container.append(fragment);
    return container.innerHTML;
  };
}
