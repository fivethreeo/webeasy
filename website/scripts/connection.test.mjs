import test from "node:test";
import assert from "node:assert/strict";
import { connectionPayload } from "../src/connection.mjs";

const example = {
  protocol: "sftp",
  host: "hosting.example.test",
  port: "22",
  username: "publisher",
  password: "example-only: not a real credential",
  directory: "/public_html",
};

test("connection QR preserves passwords and carries a versioned connection schema", () => {
  const decoded = JSON.parse(connectionPayload({ ...example, password: " spaces & symbols: \" " }));
  assert.equal(decoded.version, 1);
  assert.equal(decoded.kind, "webeasy-publish");
  assert.equal(decoded.connection.port, 22);
  assert.equal(decoded.connection.password, " spaces & symbols: \" ");
});

test("rejects malformed hosts, ports, credentials, paths, and excessive QR payloads", () => {
  for (const fields of [
    { host: "sftp://example.test" },
    { port: "0" },
    { port: "65536" },
    { port: "22.5" },
    { username: "name\ninjection" },
    { password: "" },
    { directory: "../public_html" },
    { directory: "/public_html/../private" },
    { protocol: "https" },
    { password: "a".repeat(2000) },
  ]) {
    assert.throws(() => connectionPayload({ ...example, ...fields }));
  }
});
