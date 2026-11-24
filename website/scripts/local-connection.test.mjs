import test from "node:test";
import assert from "node:assert/strict";
import { createServer, request as httpRequest } from "node:http";
import { once } from "node:events";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { localConnectionMiddleware } from "./local-connection.mjs";

test("local JSON is available only through guarded setup requests and errors do not reveal credentials", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "webeasy-connection-"));
  const file = path.join(directory, "hosting.local.json");
  const middleware = localConnectionMiddleware(file);
  const server = createServer((request, response) => {
    middleware(request, response, () => response.writeHead(404).end());
  }).listen(0, "127.0.0.1");
  await once(server, "listening");
  const url = `http://127.0.0.1:${server.address().port}/__webeasy/connection`;
  const headers = { "X-Webeasy-Setup": "1" };
  try {
    const missing = await fetch(url, { headers });
    assert.equal(missing.status, 404);
    const example = { protocol: "sftp", host: "hosting.example.test", port: 22,
      username: "publisher", password: "example-not-a-real-password", directory: "/public_html" };
    await writeFile(file, JSON.stringify(example));
    const valid = await fetch(url, { headers });
    assert.equal(valid.headers.get("cache-control"), "no-store");
    assert.deepEqual((await valid.json()).connection, example);
    const page = await fetch(url.replace("/__webeasy/connection", "/provision.html"));
    const html = await page.text();
    assert.equal(page.status, 200);
    assert.match(html, /<svg[^>]+role="img"/);
    assert.match(html, /<path/);
    assert.ok(!html.includes("<canvas"));
    assert.ok(!html.includes(example.password));
    const image = await fetch(url.replace("/__webeasy/connection", "/provision.svg"));
    assert.equal(image.headers.get("content-type"), "image/svg+xml");
    assert.equal(image.headers.get("cache-control"), "no-store");
    assert.match(await image.text(), /<svg/);
    assert.equal((await fetch(url)).status, 403);
    assert.equal((await fetch(url, { headers: { ...headers, Origin: "https://untrusted.example" } })).status, 403);
    assert.equal((await fetch(url, { method: "POST", headers })).status, 403);
    const invalidHostStatus = await new Promise((resolve, reject) => {
      const request = httpRequest(url, { headers: { ...headers, Host: "untrusted.example" } }, (response) => {
        response.resume();
        resolve(response.statusCode);
      });

      request.on("error", reject);
      request.end();
    });
    assert.equal(invalidHostStatus, 403);
    await writeFile(file, JSON.stringify({ ...example, host: "invalid url" }));
    const invalid = await fetch(url, { headers });
    assert.equal(invalid.status, 422);
    assert.ok(!(await invalid.text()).includes(example.password));
    const invalidPage = await fetch(url.replace("/__webeasy/connection", "/provision.html"));
    assert.equal(invalidPage.status, 422);
    assert.ok((await invalidPage.text()).includes('id="qr-result" hidden'));
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
});

test("a remote LAN client cannot obtain credentials by spoofing this machine's Host header", async () => {
  let status;
  const response = {
    setHeader() {},
    writeHead(value) { status = value; return this; },
    end() {},
  };
  for (const url of ["/__webeasy/connection", "/provision.html", "/provision.svg"]) {
    await localConnectionMiddleware("/unused-local-file")({
      url,
      method: "GET",
      headers: { host: "192.168.1.2:4173", "x-webeasy-setup": "1" },
      socket: { localPort: 4173, localAddress: "192.168.1.2", remoteAddress: "192.168.1.99" },
    }, response, () => assert.fail("Unexpected middleware fallthrough"));
    assert.equal(status, 403);
  }
});
