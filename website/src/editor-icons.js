import icons from "./prosango-icons.json";

const historyIcons = {
  undo: { width: 24, height: 24, path: "M9 4 2 11l7 7v-5h6a5 5 0 0 1 5 5v2h2v-2a7 7 0 0 0-7-7H9V4Z" },
  redo: { width: 24, height: 24, path: "M15 4l7 7-7 7v-5H9a5 5 0 0 0-5 5v2H2v-2a7 7 0 0 1 7-7h6V4Z" },
};

export function editorIcon(button, name) {
  const icon = icons[name] ?? historyIcons[name];
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
