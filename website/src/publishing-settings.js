export function normalizePublishingSettings(input = {}) {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new Error("Invalid publishing settings.");
  const path = (key) => {
    if (input[key] !== undefined && typeof input[key] !== "string") throw new Error("Publishing paths must be text.");
    const value = (input[key] ?? "").trim();
    if (value.length > 512) throw new Error("Publishing paths are too long.");
    return value;
  };
  const parts = (value) => value.split("/").every((part) =>
    /^[a-zA-Z0-9._-]+$/.test(part) && part !== "." && part !== "..");
  const publicPath = path("publicPathPrefix");
  const uploadPath = path("uploadSubfolder");
  const publicBody = publicPath.replace(/^\/|\/$/g, "");
  const uploadBody = uploadPath.replace(/\/$/, "");
  if (publicPath && publicPath !== "/" && !parts(publicBody)) {
    throw new Error("Use a public path such as /host/site/, without a hostname, query or parent segments.");
  }
  if (uploadPath && (uploadPath.startsWith("/") || !parts(uploadBody))) {
    throw new Error("Use a relative upload subfolder such as site, without parent segments.");
  }
  return {
    publicPathPrefix: publicPath === "/" ? "/" : publicBody ? `/${publicBody}/` : "",
    uploadSubfolder: uploadBody,
  };
}
