export const maximumGeneratedIdLength = 25;

export function slugifyId(text) {
  const slug = text.normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, maximumGeneratedIdLength)
    .replace(/-+$/g, "");
  return slug || "label";
}

export function uniqueHeadingId(text, usedIds) {
  const base = slugifyId(text);
  let id = base;
  for (let suffix = 1; usedIds.has(id); suffix += 1) {
    const ending = `-${suffix}`;
    id = `${base.slice(0, maximumGeneratedIdLength - ending.length).replace(/-+$/g, "")}${ending}`;
  }
  usedIds.add(id);
  return id;
}
