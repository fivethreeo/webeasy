import icons from "./prosango-icons.json";

export function editorIcon(button, name) {
  const icon = icons[name];
  if (!icon) return;
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `0 0 ${icon.width} ${icon.height}`);
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  svg.classList.add("editor-icon");
  const path = document.createElementNS(svg.namespaceURI, "path");
  path.setAttribute("d", icon.path);
  svg.append(path);
  button.replaceChildren(svg);
  button.classList.add("icon-button");
}
