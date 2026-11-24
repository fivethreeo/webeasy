for (const container of document.querySelectorAll("[data-disclosures]")) {
  const disclosures = [...container.querySelectorAll("details")];
  if (!disclosures.length) continue;
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "Expand all details";
  button.addEventListener("click", () => {
    const open = disclosures.some((item) => !item.open);
    for (const item of disclosures) item.open = open;
    button.textContent = open ? "Collapse all details" : "Expand all details";
  });
  container.prepend(button);
}
import "./site.css";

