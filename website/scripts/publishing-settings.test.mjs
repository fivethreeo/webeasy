import test from "node:test";
import assert from "node:assert/strict";
import { normalizePublishingSettings } from "../src/publishing-settings.js";

test("public and upload paths normalize independently, including automatic and domain-root publishing", () => {
  assert.deepEqual(normalizePublishingSettings(), { publicPathPrefix: "", uploadSubfolder: "" });
  assert.deepEqual(normalizePublishingSettings({ publicPathPrefix: " /host/site ", uploadSubfolder: " site/ " }),
    { publicPathPrefix: "/host/site/", uploadSubfolder: "site" });
  assert.deepEqual(normalizePublishingSettings({ publicPathPrefix: "/", uploadSubfolder: "preview/build" }),
    { publicPathPrefix: "/", uploadSubfolder: "preview/build" });
});

test("publishing paths reject traversal, absolute upload paths, encoded escapes and URL injection", () => {
  for (const value of ["../private", "site/../private", "site//private", "site/%2e%2e/private",
    "https://example.com", "site?query", "site#fragment", "site\\private", "site\nprivate", "x".repeat(513), 42]) {
    for (const field of ["publicPathPrefix", "uploadSubfolder"]) {
      assert.throws(() => normalizePublishingSettings({ [field]: value }), undefined, `${field}: ${value}`);
    }
  }
  for (const uploadSubfolder of ["/", "/site", "//site"]) {
    assert.throws(() => normalizePublishingSettings({ uploadSubfolder }), /relative upload/);
  }
});
