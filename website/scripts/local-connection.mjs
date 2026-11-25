import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { isIP } from "node:net";
import QRCode from "qrcode";
import { escapeHtml } from "./catalog.mjs";
import { connectionPayload } from "../src/connection.mjs";

function privateClient(address) {
  if (isIP(address) === 4) {
    const [first, second] = address.split(".").map(Number);
    return first === 127 || first === 10 || (first === 172 && second >= 16 && second <= 31) ||
      (first === 192 && second === 168) || (first === 169 && second === 254);
  }
  if (isIP(address) === 6) {
    const first = Number.parseInt(address.split(":")[0], 16);
    return address === "::1" || (first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80;
  }
  return false;
}

export function localConnectionMiddleware(file) {
  return async (request, response, next) => {
    const pathname = request.url?.split("?")[0];
    const page = pathname === "/provision.html";
    const image = pathname === "/provision.svg";
    if (!page && !image && pathname !== "/__webeasy/connection") {
      next();
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Type", "application/json");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    response.setHeader("Referrer-Policy", "no-referrer");
    const host = request.headers.host;
    const localAddress = request.socket.localAddress?.replace(/^::ffff:/, "");
    const remoteAddress = request.socket.remoteAddress?.replace(/^::ffff:/, "");
    const localClient = typeof remoteAddress === "string" && (remoteAddress === localAddress || privateClient(remoteAddress));
    const validHost = ["127.0.0.1", "localhost", localAddress]
      .filter(Boolean).map((address) => `${address.includes(":") ? `[${address}]` : address}:${request.socket.localPort}`).includes(host);
    const origin = request.headers.origin;
    if (request.method !== "GET" || !validHost || !localClient ||
        (!page && !image && request.headers["x-webeasy-setup"] !== "1") ||
        (origin && origin !== `http://${host}`)) {
      response.writeHead(403).end(JSON.stringify({ error: "Private LAN setup requests only." }));
      return;
    }
    let template;
    function renderPage(svg, message, ftp = false) {
      return template
        .replace("<!-- CONNECTION_QR -->", svg)
        .replace("<!-- CONNECTION_STATUS -->", escapeHtml(message))
        .replace('id="qr-result"', svg ? 'id="qr-result"' : 'id="qr-result" hidden')
        .replace('id="ftp-warning" hidden', ftp ? 'id="ftp-warning"' : 'id="ftp-warning" hidden');
    }
    try {
      if (page) {
        template = await readFile(fileURLToPath(new URL("../src/provision.html", import.meta.url)), "utf8");
      }
      const fields = JSON.parse(await readFile(file, "utf8"));
      const payload = connectionPayload(fields);
      if (page || image) {
        const svg = (await QRCode.toString(payload, {
          type: "svg", errorCorrectionLevel: "M", width: 480, margin: 4,
        })).replace("<svg ", '<svg role="img" aria-label="Hosting connection QR code" ');
        if (image) {
          response.setHeader("Content-Type", "image/svg+xml");
          response.writeHead(200).end(svg);
        } else {
          const html = renderPage(svg, "QR generated from hosting.local.json.", fields.protocol === "ftp");
          response.setHeader("Content-Type", "text/html; charset=utf-8");
          response.writeHead(200).end(html);
        }
      } else {
        response.writeHead(200).end(payload);
      }
    } catch (error) {
      const missing = error.code === "ENOENT";
      const message = missing ? "No hosting.local.json found. Create the local file and reload the QR."
        : "Unable to generate the QR. Check hosting.local.json connection fields.";
      if (page && template) {
        response.setHeader("Content-Type", "text/html; charset=utf-8");
        response.writeHead(missing ? 404 : 422).end(renderPage("", message));
      } else {
        response.writeHead(missing ? 404 : 422).end(JSON.stringify({ error: message }));
      }
    }
  };
}
