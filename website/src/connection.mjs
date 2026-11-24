export function connectionPayload(fields) {
  const protocol = fields.protocol;
  const host = fields.host?.trim();
  const username = fields.username?.trim();
  const password = fields.password;
  const directory = fields.directory?.trim();
  const port = Number(fields.port);
  if (!["sftp", "ftps", "ftp"].includes(protocol)) throw new Error("Choose SFTP, FTPS, or FTP.");
  if (!host || /[\s/\\?#@]/.test(host) || host.includes("://")) {
    throw new Error("Enter a hostname or IP address, not a URL.");
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Port must be between 1 and 65535.");
  if (!username || /[\r\n\0]/.test(username)) throw new Error("Enter a valid username.");
  if (typeof password !== "string" || !password || /[\r\n\0]/.test(password)) {
    throw new Error("Enter a valid password.");
  }
  if (!directory || !directory.startsWith("/") || /[\r\n\0]/.test(directory) ||
      directory.split("/").includes("..")) {
    throw new Error("Use an absolute publishing folder without parent-directory traversal.");
  }
  const payload = JSON.stringify({
    version: 1,
    kind: "webeasy-publish",
    connection: { protocol, host, port, username, password, directory },
  });
  if (new TextEncoder().encode(payload).length > 1800) {
    throw new Error("Connection details are too long for a reliably scannable QR code.");
  }
  return payload;
}
