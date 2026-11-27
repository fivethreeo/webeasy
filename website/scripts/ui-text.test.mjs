import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { setUiLanguage, uiLanguage, uiText } from "../src/ui-text.js";

test("Bokmål localizes interface labels while English and unsupported languages keep the original", () => {
  assert.equal(uiText("Save", "nb-NO"), "Lagre");
  assert.equal(uiText("Pages", "no-NO"), "Sider");
  assert.equal(uiText("Save", "en-GB"), "Save");
  assert.equal(uiText("Save", "de"), "Save");
  assert.equal(uiText("My own title", "nb"), "My own title");
  assert.equal(uiLanguage("nn-NO"), "en");
});

test("switching interface language never translates content, preview text or entered values", () => {
  const dom = new JSDOM(`<html><body><label>Alternative text<input value="Pages"></label>
    <button title="Undo">Save</button><div class="rich-editor" contenteditable="true"><p>Pages</p></div>
    <div id="preview">Save</div><div id="pages">Pages</div>
    <select data-user-content><option>Pages</option></select></body></html>`);
  const document = dom.window.document;
  setUiLanguage("nb-NO", document);
  assert.equal(document.documentElement.lang, "nb");
  assert.equal(document.querySelector("button").textContent, "Lagre");
  assert.equal(document.querySelector("button").title, "Angre");
  assert.match(document.querySelector("label").textContent, /Alternativ tekst/);
  assert.equal(document.querySelector("input").value, "Pages");
  assert.equal(document.querySelector(".rich-editor").innerHTML, "<p>Pages</p>");
  assert.equal(document.querySelector("#preview").textContent, "Save");
  assert.equal(document.querySelector("#pages").textContent, "Pages");
  assert.equal(document.querySelector("option").textContent, "Pages");
  setUiLanguage("en", document);
  assert.equal(document.querySelector("button").textContent, "Save");
  assert.equal(document.querySelector("button").title, "Undo");
});
