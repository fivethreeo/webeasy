import test from "node:test";
import assert from "node:assert/strict";
import { connectionPayload, readConnectionQR } from "../src/connection.mjs";

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

test("QR imports round-trip exact passwords and reject wrong envelopes and invalid types", () => {
  const payload = JSON.parse(connectionPayload(example));
  assert.equal(readConnectionQR(JSON.stringify(payload)).password, example.password);
  for (const input of [
    "not JSON", "null", JSON.stringify({ ...payload, version: 2 }),
    JSON.stringify({ ...payload, kind: "other" }),
    JSON.stringify({ ...payload, connection: { ...payload.connection, port: "22" } }),
    JSON.stringify({ ...payload, connection: { ...payload.connection, host: 123 } }),
    JSON.stringify({ ...payload, connection: { ...payload.connection, password: "x".repeat(2000) } }),
  ]) assert.throws(() => readConnectionQR(input));
});
