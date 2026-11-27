import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { mkdirSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { createRichTextSanitizer, localMediaPath, safeLinkHref } from "../../website/src/rich-text.js";
import { uniqueHeadingId } from "../../website/src/heading-id.js";
import { rendererSources, rendererFlags } from "../../scripts/renderer-sources.mjs";

// Differential fixtures test the native sanitizer against the editor's DOM
// implementation. An override also lets this suite use an ASan/UBSan build.
const executable = process.env.WEBEASY_HTML_TEST_EXECUTABLE ?? path.resolve("dist/renderer/html-test");
if (!process.env.WEBEASY_HTML_TEST_EXECUTABLE) {
  mkdirSync(path.dirname(executable), { recursive: true });
  const build = spawnSync(process.env.CC || "cc", [...rendererFlags, "-O1",
    "-DWEBEASY_HTML_THREADS", "-pthread",
    fileURLToPath(new URL("./test-html.c", import.meta.url)), ...rendererSources(), "-lm", "-o", executable],
  { encoding: "utf8" });
  assert.equal(build.status, 0, build.error?.message ?? build.stdout + build.stderr);
}
const checks = spawnSync(executable, [], { encoding: "utf8" });
assert.equal(checks.status, 0, checks.error?.message ?? checks.stdout + checks.stderr);
const window = new JSDOM("").window;
const sanitize = createRichTextSanitizer(window);
const short = createRichTextSanitizer(window, true);
function c(mode, ...arguments_) {
  const result = spawnSync(executable, [mode, ...arguments_], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  return result.stdout;
}

const fixtures = [
  "",
  "Text <strong>bold</strong>!",
  "It&nbsp;<strong>works</strong>!<p></p>",
  '<h2>Heading</h2>\nFirst <strong>bold</strong>!<p>Existing</p>Next <a href="about/">link</a>' +
    '<figure><img src="media/photo.jpg"><figcaption>Caption</figcaption></figure>Last<br>line',
  '<p><b>Bold <a href="about/">link</a></b> and <strong>strong</strong>.</p>',
  '<h2>Intro <strong>bold</strong></h2><ul><li>One</li><li><em>Two</em></li></ul>' +
    '<blockquote>Quote</blockquote><figure><img src="media/photo.jpg"><figcaption>Caption</figcaption>' +
    '</figure><div>New paragraph <a href="/about/">link</a></div>',
  '<h1>Heading</h1><p><b>B</b><strong>S</strong><i>I</i><em>E</em><u>U</u><s>S</s><del>D</del></p>' +
    '<ul><li>One</li></ul><ol><li>Two</li></ol><blockquote>Quote</blockquote>' +
    '<img src="media/photo.jpg" alt="A photo" onerror="alert(1)" style="color:red">' +
    '<figure><video autoplay src="https://example.invalid/clip.mp4" poster="assets/coast.svg">' +
    '<source src="media/clip.mp4" type="video/mp4"></video><figcaption>Caption</figcaption></figure>' +
    '<script>alert(1)</script><iframe src="https://example.invalid"></iframe><svg onload="alert(1)"></svg>',
  '<p>Before</p><figure><img src="assets/coast.svg" alt="Coast">Our <strong>coast</strong>' +
    '<p>A note.</p><figcaption>Existing caption.</figcaption></figure><p>After</p>',
  '<figure><figcaption>Caption <span><img src="media/a.jpg">Text</span></figcaption>' +
    '<figure>Nested<video src="media/a.mp4"></video></figure></figure>',
  '<figure><video src="media/clip.mp4"></video></figure>',
  '<img src="media/a.jpg" width="00" height="12" loading="bad" alt=" &quot;&lt; ">',
  '<video preload="bad" width="01"><source src="media/a.webm" type="video/webm"></video>',
  '<p>Text<br></p> \t<figure>&nbsp;</figure> <source src="media/orphan.mp4">',
  '<p><strong>A<i>B</strong>C</i>D</p>',
  '<div><p>Keep</p>Loose</div><table><tr><td>Cell</td></tr></table>',
  '<a href="java&#10;script:bad()" target="_blank">Text</a><template><p>Hidden</p></template>',
  '<svg><foreignObject><p onclick="bad()">Hidden</p></foreignObject></svg>' +
    '<math><mtext><img src="media/a.jpg" onerror="bad()"></mtext></math>',
  '<!--comment--><p title="bad" data-x="bad" aria-label="bad" style="bad">Safe</p>',
  '<p><a HREF="https://example.org/" href="javascript:bad()">First duplicate wins</a></p>',
  '<textarea>Visible &lt;b&gt;not markup&lt;/b&gt;</textarea><button>Button text</button>',
  '<p>Unicode Ångström ﬁ ① &amp; &lt; &gt; "&nbsp;</p>',
];
for (const [index, fixture] of fixtures.entries()) {
  for (const [mode, reference] of [["sanitize", sanitize], ["short", short]]) {
    test(`${mode} matches editor fixture ${index}`, () => {
      const actual = c(mode, fixture), expected = reference(fixture);
      // Lexbor additionally escapes '<' inside quoted attributes. This is a
      // deliberate, DOM-equivalent serialization difference, not relaxed HTML.
      const compatible = (html) => index === 10 && mode === "sanitize" ?
        html.replace('alt="&quot;<"', 'alt="&quot;&lt;"') : html;
      assert.equal(actual, compatible(expected));
      assert.equal(c(mode, actual), compatible(reference(actual)));
      const document = new JSDOM(actual).window.document;
      assert.equal(document.querySelector(
        "script,style,iframe,svg,math,template,[onerror],[onclick],[style],[data-x],[aria-label]"), null);
    });
  }
}

const hrefs = [
  "https://example.com/path?q=1", "http://example.com/", "mailto:hello@example.com",
  "tel:+4712345678", "/", "index.html", "about/team/", "#section", "./index.html", "./",
  "javascript:alert(1)", "java\nscript:alert(1)", "data:text/html,hello", "file:///private/secret",
  "content://photos/1", "//example.com/", "\\\\example.com", "../private", "about/%2e%2e/private",
  "mailto:", "tel:", "https://example.org:99999/", "https://[invalid]/", "https://[::1]:443/",
  "https://", "https:///example.org", "https://éxample.org", "https://127.0.0.999/",
  "?query=%", "path%FF", "%ED%A0%80", "path%2Fchild", "about%2f..%2fprivate",
  "%", "%zz", "%00", "%3Ajavascript", "about/?next=../x", "a:b", "a#b:c", "",
];
for (const href of hrefs) {
  test(`URL parity ${JSON.stringify(href)}`, () => {
    assert.equal(Number(c("href", href)), Number(safeLinkHref(href)));
    if (href && !/["<&]/.test(href)) {
      const input = `<a href="${href}" title="More" onclick="bad()">Keep</a>`;
      assert.equal(c("sanitize", input), sanitize(input));
    }
  });
}
for (const path of [
  "media/photo.jpg", "assets/coast.svg", "media/a-b_2.3/file.jpg", "media/a//file", "media/a/",
  "https://example.invalid/p.jpg", "//example.invalid/p.jpg", "javascript:alert(1)",
  "data:image/png;base64,AAAA", "file:///private/photo.jpg", "content://photos/1",
  "media/../secret.jpg", "media/%2e%2e/secret.jpg", "/media/photo.jpg", "media/.hidden", "",
]) {
  test(`media path parity ${JSON.stringify(path)}`, () => {
    assert.equal(Number(c("media", path)), Number(localMediaPath(path)));
    assert.equal(c("sanitize", `<img src="${path}">`), sanitize(`<img src="${path}">`));
  });
}
test("NFKD IDs, 25-character truncation and collision suffixes match Prosango", () => {
  const headings = [
    "Héllo & World!", "Hello world", "Hello world", "Hello world-1", "!!!", "!!!",
    "a".repeat(40), "a".repeat(40), "ﬁ Ｆｕｌｌ ① ß", "Ångström Ø Æ ß", "a͏b",
    "İ ı ſ K", "東京", "a".repeat(24) + "---b", "a".repeat(24) + "---b",
    "Ḋeja vu", "👩‍💻 Work!", "⒈ Test", "À déjà ﬁancée", "ℌ ℍ ℎ",
  ];
  const used = new Set();
  assert.deepEqual(c("ids", ...headings).trimEnd().split("\n"),
    headings.map((heading) => uniqueHeadingId(heading, used)));
});
test("home rewrites touch only anchors and preserve text/title and safe escaping", () => {
  const input = sanitize('<p><a href="/" title="literal\n href=\'/\'">Home</a>' +
    '<a href="index.html#details">Details</a><a href="/?one=1&amp;two=2">Search</a>' +
    '<a href="about/">Page</a><a href="/other/">Other</a>Literal href=\'/\'</p>');
  for (const home of ["/host/site/", "/", "./", "home.html", "/host/?one=1&two=2"]) {
    const output = new JSDOM(c("rich", input, home)).window.document;
    const anchors = [...output.querySelectorAll("a")];
    assert.deepEqual(anchors.map((anchor) => anchor.getAttribute("href")),
      [home, `${home}#details`, `${home}?one=1&two=2`, "about/", "/other/"]);
    assert.equal(anchors[0].getAttribute("title"), "literal\n href='/'");
    assert.match(output.body.textContent, /Literal href='\/'/);
  }
  const untouched = "<p><a href='about/'>It&nbsp;works</a></p>";
  assert.equal(c("rich", untouched, "./"), untouched);
  const sameHome = "<p><a href='/#section'>It&nbsp;works</a></p>";
  assert.equal(c("rich", sameHome, "/"), sameHome);
});
