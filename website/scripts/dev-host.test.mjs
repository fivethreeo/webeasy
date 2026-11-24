import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import { mkdtemp, writeFile, symlink, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { devHostMiddleware } from "./dev-host.mjs";

test("development uploads are served with range support but hidden files and escaping symlinks are not", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "webeasy-host-"));
  const outside = await mkdtemp(path.join(tmpdir(), "webeasy-outside-"));
  await writeFile(path.join(directory, "index.html"), "<h1>Uploaded page</h1>");
  await writeFile(path.join(directory, "clip.mp4"), "0123456789");
  await writeFile(path.join(directory, ".pending.upload"), "unpublished");
  await writeFile(path.join(outside, "private.txt"), "private");
  await symlink(path.join(outside, "private.txt"), path.join(directory, "escape.txt"));
  const middleware = devHostMiddleware(directory);
  const server = createServer((request, response) => middleware(request, response,
    () => response.writeHead(404).end())).listen(0, "127.0.0.1");
  await once(server, "listening");
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal(await (await fetch(`${url}/host/`)).text(), "<h1>Uploaded page</h1>");
    const range = await fetch(`${url}/host/clip.mp4`, { headers: { Range: "bytes=2-5" } });
    assert.equal(range.status, 206);
    assert.equal(range.headers.get("content-range"), "bytes 2-5/10");
    assert.equal(await range.text(), "2345");
    assert.equal((await fetch(`${url}/host/clip.mp4`, { headers: { Range: "bytes=99-" } })).status, 416);
    assert.equal((await fetch(`${url}/host/.pending.upload`)).status, 403);
    assert.equal((await fetch(`${url}/host/escape.txt`)).status, 403);
    assert.equal((await fetch(`${url}/host/`, { method: "POST" })).status, 405);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});
