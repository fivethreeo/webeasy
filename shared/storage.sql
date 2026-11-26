CREATE TABLE IF NOT EXISTS metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pages (
    id TEXT PRIMARY KEY NOT NULL,
    parent_id TEXT REFERENCES pages(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    menu_title TEXT NOT NULL,
    template_id TEXT NOT NULL DEFAULT 'page',
    cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL,
    cover_alt TEXT NOT NULL DEFAULT '',
    slug TEXT NOT NULL,
    content TEXT NOT NULL,
    date TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0)
);
CREATE UNIQUE INDEX IF NOT EXISTS root_page_slug ON pages(slug) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS child_page_slug ON pages(parent_id, slug) WHERE parent_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS news (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    slug TEXT NOT NULL,
    date TEXT NOT NULL,
    short_content TEXT NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    cover_media_id TEXT REFERENCES media(id) ON DELETE SET NULL,
    cover_alt TEXT NOT NULL DEFAULT '',
    UNIQUE(date, slug)
);
CREATE TABLE IF NOT EXISTS sections (
    id TEXT PRIMARY KEY NOT NULL,
    page_id TEXT REFERENCES pages(id) ON DELETE CASCADE,
    news_id TEXT REFERENCES news(id) ON DELETE CASCADE,
    heading TEXT NOT NULL,
    subheading TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    CHECK ((page_id IS NOT NULL) + (news_id IS NOT NULL) = 1)
);
CREATE TABLE IF NOT EXISTS asides (
    id TEXT PRIMARY KEY NOT NULL,
    page_id TEXT REFERENCES pages(id) ON DELETE CASCADE,
    news_id TEXT REFERENCES news(id) ON DELETE CASCADE,
    section_id TEXT REFERENCES sections(id) ON DELETE CASCADE,
    heading TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    CHECK ((page_id IS NOT NULL) + (news_id IS NOT NULL) + (section_id IS NOT NULL) = 1)
);
CREATE TABLE IF NOT EXISTS media (
    id TEXT PRIMARY KEY NOT NULL,
    filename TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    original_name TEXT NOT NULL,
    size INTEGER NOT NULL CHECK (size > 0)
);
