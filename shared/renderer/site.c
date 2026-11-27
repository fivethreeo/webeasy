#include "site.h"
#include "renderer.h"
#include "html.h"
#include "vendor/cjson/cJSON.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Reading guide: the public webeasy_* functions at the bottom call operation().
 * It validates a project with normalize(), builds template data with prepare(),
 * and optionally asks renderer.c for HTML. html.c handles rich-text safety.
 * A "static" function is private to this C file; it is not an exported API. */

#define SITE_LIMIT (64u * 1024u * 1024u)
#define TREE_DEPTH 64

/* One operation owns all of its state. There are no shared mutable globals, so
 * Android worker threads and Swift detached tasks can generate independently.
 * Helpers receive a struct site *: a pointer to this state, not a copied struct.
 * site->error therefore accesses the error field in the caller's same state. */
struct site {
    char error[256];
    cJSON *ids;
    cJSON *project;
    cJSON *partials;
    const cJSON *catalog; /* Borrowed from input; do not delete separately. */
    cJSON *routes;
    cJSON *documents;
    char *home_href;
    const cJSON *home; /* Borrowed nodes inside the owned routes tree. */
    const cJSON *home_entry;
};

/* error is a fixed-size char array. Its first byte is '\0' until a failure;
 * keep the first message so later cleanup does not hide the original cause. */
static void fail(struct site *site, const char *message)
{
    if (!*site->error) snprintf(site->error, sizeof site->error, "%s", message);
}

static cJSON *get(const cJSON *object, const char *key)
{
    return cJSON_GetObjectItemCaseSensitive(object, key);
}

static const char *string(const cJSON *object, const char *key)
{
    const cJSON *value = get(object, key);
    return cJSON_IsString(value) ? value->valuestring : "";
}

static cJSON *object(struct site *site)
{
    cJSON *value = cJSON_CreateObject();
    if (!value) fail(site, "Out of memory.");
    return value;
}

static cJSON *array(struct site *site)
{
    cJSON *value = cJSON_CreateArray();
    if (!value) fail(site, "Out of memory.");
    return value;
}

/* These helpers take ownership even on failure. That makes the many JSON tree
 * transformations below readable without leaking detached values on errors.
 * After set()/push(), the parent owns value: deleting the parent also deletes
 * that child. The caller must not delete the child a second time. */
static void set(struct site *site, cJSON *parent, const char *key, cJSON *value)
{
    if (!value || !parent) {
        cJSON_Delete(value);
        fail(site, "Out of memory.");
        return;
    }
    cJSON_DeleteItemFromObjectCaseSensitive(parent, key);
    if (!cJSON_AddItemToObject(parent, key, value)) {
        cJSON_Delete(value);
        fail(site, "Out of memory.");
    }
}

static void push(struct site *site, cJSON *parent, cJSON *value)
{
    if (!value || !parent || !cJSON_AddItemToArray(parent, value)) {
        cJSON_Delete(value);
        fail(site, "Out of memory.");
    }
}

/* get() returns a borrowed pointer into an existing tree. To put that data in
 * another tree, duplicate it: cJSON_Duplicate(..., 1) also copies descendants. */
static cJSON *copy(struct site *site, const cJSON *value)
{
    cJSON *result = value ? cJSON_Duplicate(value, 1) : NULL;
    if (!result) fail(site, "Out of memory.");
    return result;
}

static void text(struct site *site, cJSON *parent, const char *key, const char *value)
{
    set(site, parent, key, cJSON_CreateString(value));
}

static void flag(struct site *site, cJSON *parent, const char *key, int value)
{
    set(site, parent, key, cJSON_CreateBool(value));
}

static int alpha(unsigned char c)
{
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

static int digit(unsigned char c)
{
    return c >= '0' && c <= '9';
}

/* Match JavaScript String.trim(), including NBSP and the Unicode whitespace
 * commonly pasted from rich text. Work on UTF-8 boundaries, never byte slices
 * through a Norwegian letter or another multibyte character. */
static size_t space(const char *text)
{
    unsigned char c = (unsigned char)text[0];
    if (c == ' ' || (c >= 9 && c <= 13)) return 1;
    if (!strncmp(text, "\xc2\xa0", 2)) return 2;
    if (!strncmp(text, "\xe1\x9a\x80", 3) || !strncmp(text, "\xef\xbb\xbf", 3) ||
        !strncmp(text, "\xe2\x80\xa8", 3) || !strncmp(text, "\xe2\x80\xa9", 3) ||
        !strncmp(text, "\xe2\x80\xaf", 3) || !strncmp(text, "\xe2\x81\x9f", 3) ||
        !strncmp(text, "\xe3\x80\x80", 3)) return 3;
    if (strlen(text) >= 3 && c == 0xe2 && (unsigned char)text[1] == 0x80 &&
        (unsigned char)text[2] >= 0x80 && (unsigned char)text[2] <= 0x8a) return 3;
    return 0;
}

static char *trim(struct site *site, const char *value)
{
    while (space(value)) value += space(value);
    size_t length = strlen(value);
    while (length) {
        size_t start = length - 1;
        while (start && ((unsigned char)value[start] & 0xc0) == 0x80) start--;
        if (space(value + start) != length - start) break;
        length = start;
    }
    char *result = malloc(length + 1);
    if (!result) { fail(site, "Out of memory."); return NULL; }
    memcpy(result, value, length);
    result[length] = 0;
    return result;
}

/* The returned const char * borrows the record's string; callers do not free it.
 * The separately allocated trimmed copy is only used to check for blank text. */
static const char *required_text(struct site *site, const cJSON *record, const char *key, int required)
{
    const cJSON *value = get(record, key);
    if (!cJSON_IsString(value)) {
        char message[128];
        snprintf(message, sizeof message, "Missing %s.", key);
        fail(site, message);
        return "";
    }
    char *stripped = required ? trim(site, value->valuestring) : NULL;
    if (required && stripped && !*stripped) {
        char message[128];
        snprintf(message, sizeof message, "Missing %s.", key);
        fail(site, message);
    }
    free(stripped);
    return value->valuestring;
}

static void field(struct site *site, cJSON *output, const cJSON *record, const char *key, int required, int optional)
{
    text(site, output, key, optional && !get(record, key) ? "" : required_text(site, record, key, required));
}

static const cJSON *list(struct site *site, const cJSON *record, const char *key)
{
    const cJSON *value = get(record, key);
    if (value && !cJSON_IsArray(value)) {
        char message[128];
        snprintf(message, sizeof message, "%s must be an ordered list.", key);
        fail(site, message);
        return NULL;
    }
    return value;
}

static int contains(const cJSON *values, const char *text)
{
    const cJSON *value;
    cJSON_ArrayForEach(value, values)
        if (cJSON_IsString(value) && !strcmp(value->valuestring, text)) return 1;
    return 0;
}

static int valid_id(const char *id)
{
    if (!*id) return 0;
    for (; *id; id++) if (!alpha((unsigned char)*id) && !digit((unsigned char)*id) && *id != '_' && *id != '-') return 0;
    return 1;
}

static void identity(struct site *site, cJSON *output, const cJSON *record)
{
    const char *id = required_text(site, record, "id", 1);
    if (!valid_id(id) || contains(site->ids, id)) fail(site, "Content IDs must be unique.");
    push(site, site->ids, cJSON_CreateString(id));
    text(site, output, "id", id);
}

static int valid_slug(const char *slug)
{
    int previous_hyphen = 1;
    for (; *slug; slug++) {
        if (*slug == '-') {
            if (previous_hyphen) return 0;
            previous_hyphen = 1;
        } else {
            if (!(*slug >= 'a' && *slug <= 'z') && !digit((unsigned char)*slug)) return 0;
            previous_hyphen = 0;
        }
    }
    return !previous_hyphen;
}

static void slug(struct site *site, cJSON *output, const cJSON *record)
{
    const char *value = required_text(site, record, "slug", 1);
    if (!valid_slug(value)) fail(site, "Slugs use lowercase letters, numbers, and hyphens.");
    text(site, output, "slug", value);
}

static void date(struct site *site, cJSON *output, const cJSON *record)
{
    const char *value = required_text(site, record, "date", 1);
    int valid = strlen(value) == 10;
    for (int index = 0; valid && index < 10; index++)
        valid = index == 4 || index == 7 ? value[index] == '-' : digit((unsigned char)value[index]);
    if (valid) {
        int year = (value[0]-'0')*1000+(value[1]-'0')*100+(value[2]-'0')*10+value[3]-'0';
        int month = (value[5]-'0')*10+value[6]-'0', day = (value[8]-'0')*10+value[9]-'0';
        static const int days[] = {0,31,28,31,30,31,30,31,31,30,31,30,31};
        valid = month >= 1 && month <= 12 && day >= 1 &&
            day <= days[month] + (month == 2 && year % 4 == 0 && (year % 100 != 0 || year % 400 == 0));
    }
    if (!valid) fail(site, "Dates must be valid YYYY-MM-DD dates.");
    text(site, output, "date", value);
}

static void sanitize_field(struct site *site, cJSON *output, const cJSON *record, const char *key, int short_text)
{
    const char *value = required_text(site, record, key, 0);
    char *html = *site->error ? NULL : webeasy_sanitize(value, short_text);
    if (!html && !*site->error) fail(site, "Cannot sanitize rich text.");
    text(site, output, key, html ? html : "");
    free(html);
}

static cJSON *asides(struct site *site, const cJSON *record)
{
    cJSON *output = array(site);
    const cJSON *item;
    cJSON_ArrayForEach(item, list(site, record, "asides")) {
        if (*site->error) break;
        cJSON *aside = object(site);
        identity(site, aside, item);
        field(site, aside, item, "heading", 0, 1);
        sanitize_field(site, aside, item, "content", 0);
        push(site, output, aside);
    }
    return output;
}

static cJSON *sections(struct site *site, const cJSON *record)
{
    cJSON *output = array(site);
    const cJSON *item;
    cJSON_ArrayForEach(item, list(site, record, "sections")) {
        if (*site->error) break;
        cJSON *section = object(site);
        identity(site, section, item);
        field(site, section, item, "heading", 1, 0);
        field(site, section, item, "subheading", 0, 1);
        sanitize_field(site, section, item, "content", 0);
        set(site, section, "asides", asides(site, item));
        push(site, output, section);
    }
    return output;
}

static void cover_fields(struct site *site, cJSON *output, const cJSON *record)
{
    field(site, output, record, "coverMediaId", 0, 1);
    field(site, output, record, "coverAlt", 0, 1);
    const char *id = string(output, "coverMediaId");
    if (*id && !valid_id(id)) fail(site, "Invalid cover image ID.");
}

static cJSON *pages(struct site *site, const cJSON *records, int depth)
{
    cJSON *output = array(site), *slugs = array(site);
    if (depth > TREE_DEPTH) { fail(site, "Content tree is too deep."); cJSON_Delete(slugs); return output; }
    const cJSON *record;
    static const char *reserved[] = {"news","assets","media","examples","components","editor","provision","catalog","content","index"};
    cJSON_ArrayForEach(record, records) {
        if (*site->error) break;
        cJSON *page = object(site);
        slug(site, page, record);
        const char *value = string(page, "slug");
        int duplicate = contains(slugs, value);
        if (!depth) for (size_t index = 0; index < sizeof reserved / sizeof *reserved; index++)
            duplicate |= !strcmp(value, reserved[index]);
        if (duplicate) {
            char message[256];
            snprintf(message, sizeof message, "Duplicate or reserved page slug: %s", value);
            fail(site, message);
        }
        push(site, slugs, cJSON_CreateString(value));
        identity(site, page, record);
        field(site, page, record, "title", 1, 0);
        field(site, page, record, "author", 0, 1);
        field(site, page, record, "subtitle", 0, 1);
        field(site, page, record, "menuTitle", 1, 0);
        const char *template = get(record, "templateId") ? required_text(site, record, "templateId", 1) : "page";
        if (!(*template >= 'a' && *template <= 'z')) fail(site, "Invalid page layout ID.");
        for (const char *p = template; *p; p++)
            if (!(*p >= 'a' && *p <= 'z') && !digit((unsigned char)*p) && *p != '-') fail(site, "Invalid page layout ID.");
        text(site, page, "templateId", template);
        sanitize_field(site, page, record, "content", 0);
        date(site, page, record);
        cover_fields(site, page, record);
        set(site, page, "sections", sections(site, record));
        set(site, page, "asides", asides(site, record));
        set(site, page, "children", pages(site, list(site, record, "children"), depth + 1));
        push(site, output, page);
    }
    cJSON_Delete(slugs);
    return output;
}

static char *join(struct site *site, const char *a, const char *b, const char *c)
{
    size_t length = strlen(a) + strlen(b) + strlen(c);
    if (length > SITE_LIMIT) { fail(site, "Generated path is too large."); return NULL; }
    char *result = malloc(length + 1);
    if (!result) { fail(site, "Out of memory."); return NULL; }
    snprintf(result, length + 1, "%s%s%s", a, b, c);
    return result;
}

static int extension(const char *filename, int image_only)
{
    const char *dot = strrchr(filename, '.');
    if (!dot) return 0;
    static const char *types[] = {"jpg","jpeg","png","gif","webp","mp4","webm","mov","ogg"};
    for (int index = 0; index < (image_only ? 5 : 9); index++) if (!strcmp(dot + 1, types[index])) return 1;
    return 0;
}

static const cJSON *cover(struct site *site, const cJSON *record)
{
    const char *id = string(record, "coverMediaId");
    if (!*id) return NULL;
    const cJSON *item;
    cJSON_ArrayForEach(item, get(site->project, "media")) {
        if (strcmp(string(item, "id"), id)) continue;
        if (!strncmp(string(item, "mimeType"), "image/", 6) && extension(string(item, "filename"), 1)) return item;
        break;
    }
    fail(site, "Choose an existing managed image for the cover.");
    return NULL;
}

static const cJSON *find_page(const cJSON *records, const char *id, int depth)
{
    if (depth > TREE_DEPTH) return NULL;
    const cJSON *page;
    cJSON_ArrayForEach(page, records) {
        if (!strcmp(string(page, "id"), id)) return page;
        const cJSON *found = find_page(get(page, "children"), id, depth + 1);
        if (found) return found;
    }
    return NULL;
}

static void check_covers(struct site *site, const cJSON *records, int depth)
{
    if (depth > TREE_DEPTH || *site->error) return;
    const cJSON *record;
    cJSON_ArrayForEach(record, records) {
        cover(site, record);
        check_covers(site, get(record, "children"), depth + 1);
    }
}

/* Normalize into a fresh, deliberately restricted schema. Editor-only fields
 * never leak into SQLite snapshots or templates. IDs are unique across every
 * kind of record, while page slugs are unique only among their siblings. */
/* Rebuild a clean project rather than editing the parsed input. This centralizes
 * validation for save and publish alike: IDs, routes, media and sanitized HTML
 * must be consistent before any template sees them. No files are written here. */
static cJSON *normalize(struct site *site, const cJSON *input)
{
    cJSON *project = object(site);
    site->project = project;
    const cJSON *version = get(input, "version");
    if (!cJSON_IsNumber(version) || version->valuedouble != 1 ||
        !cJSON_IsArray(get(input, "pages")) || !cJSON_IsArray(get(input, "news"))) {
        fail(site, "Unsupported content project.");
        return project;
    }
    set(site, project, "version", cJSON_CreateNumber(1));
    set(site, project, "pages", pages(site, get(input, "pages"), 0));
    cJSON *news = array(site), *news_routes = array(site);
    const cJSON *record;
    cJSON_ArrayForEach(record, get(input, "news")) {
        if (*site->error) break;
        cJSON *item = object(site);
        date(site, item, record);
        slug(site, item, record);
        char *route = join(site, string(item, "date"), "/", string(item, "slug"));
        if (route && contains(news_routes, route)) {
            char message[256];
            snprintf(message, sizeof message, "Duplicate news route: %s", route);
            fail(site, message);
        }
        if (route) push(site, news_routes, cJSON_CreateString(route));
        free(route);
        identity(site, item, record);
        field(site, item, record, "title", 1, 0);
        cJSON *legacy = copy(site, record);
        if (!get(record, "content")) text(site, legacy, "content", required_text(site, record, "shortContent", 0));
        sanitize_field(site, item, legacy, "content", 0);
        cJSON_Delete(legacy);
        sanitize_field(site, item, record, "shortContent", 1);
        set(site, item, "sections", sections(site, record));
        set(site, item, "asides", asides(site, record));
        cover_fields(site, item, record);
        push(site, news, item);
    }
    cJSON_Delete(news_routes);
    set(site, project, "news", news);
    cJSON *media = array(site), *filenames = array(site);
    cJSON_ArrayForEach(record, list(site, input, "media")) {
        if (*site->error) break;
        cJSON *item = object(site);
        const char *filename = required_text(site, record, "filename", 1);
        const char *dot = strrchr(filename, '.');
        int valid = (alpha((unsigned char)*filename) || digit((unsigned char)*filename)) &&
            dot && dot - filename >= 2 && extension(filename, 0);
        for (const char *p = filename; *p; p++)
            if (!alpha((unsigned char)*p) && !digit((unsigned char)*p) && *p != '.' && *p != '_' && *p != '-') valid = 0;
        if (!valid) fail(site, "Invalid managed media filename.");
        const cJSON *size = get(record, "size");
        if (!cJSON_IsNumber(size) || !isfinite(size->valuedouble) || size->valuedouble <= 0 ||
            size->valuedouble > 9007199254740991.0 || floor(size->valuedouble) != size->valuedouble) fail(site, "Invalid media size.");
        if (contains(filenames, filename)) fail(site, "Duplicate media filename.");
        push(site, filenames, cJSON_CreateString(filename));
        identity(site, item, record);
        text(site, item, "filename", filename);
        field(site, item, record, "mimeType", 1, 0);
        field(site, item, record, "originalName", 0, 0);
        set(site, item, "size", cJSON_CreateNumber(cJSON_IsNumber(size) ? size->valuedouble : 0));
        push(site, media, item);
    }
    cJSON_Delete(filenames);
    set(site, project, "media", media);
    check_covers(site, get(project, "pages"), 0);
    check_covers(site, news, 0);
    const cJSON *home_id = get(input, "homePageId");
    if (!home_id || cJSON_IsNull(home_id)) {
        const cJSON *first = cJSON_GetArrayItem(get(project, "pages"), 0);
        home_id = get(first, "id");
    }
    if (home_id && (!cJSON_IsString(home_id) || !find_page(get(project, "pages"), home_id->valuestring, 0)))
        fail(site, "Choose an existing page for the homepage.");
    set(site, project, "homePageId", home_id ? copy(site, home_id) : cJSON_CreateNull());
    return project;
}

/* Build routing and render contexts separately from rendering. Templates remain
 * editable data: C decides which values they receive, not their HTML layout.
 * Recursion follows page children: "about" then "team" becomes /about/team/.
 * Each child gets its own ancestor copy, keeping sibling breadcrumbs separate. */
static void routes(struct site *site, const cJSON *records, const char *prefix, const cJSON *ancestors, int depth)
{
    if (depth > TREE_DEPTH || *site->error) return;
    const cJSON *page;
    cJSON_ArrayForEach(page, records) {
        char *path = join(site, prefix, string(page, "slug"), "/");
        if (!path) return;
        cJSON *entry = object(site);
        set(site, entry, "record", copy(site, page));
        text(site, entry, "route", path);
        set(site, entry, "ancestors", copy(site, ancestors));
        push(site, site->routes, entry);
        cJSON *lineage = copy(site, ancestors);
        push(site, lineage, copy(site, page));
        routes(site, get(page, "children"), path, lineage, depth + 1);
        cJSON_Delete(lineage);
        free(path);
    }
}

static const cJSON *route_entry(struct site *site, const char *id)
{
    const cJSON *entry;
    cJSON_ArrayForEach(entry, site->routes) if (!strcmp(string(get(entry, "record"), "id"), id)) return entry;
    return NULL;
}

static cJSON *page_link(struct site *site, const cJSON *page)
{
    cJSON *link = object(site);
    for (size_t i = 0; i < 5; i++) {
        static const char *fields[] = {"id","title","menuTitle","slug","date"};
        text(site, link, fields[i], string(page, fields[i]));
    }
    const cJSON *entry = route_entry(site, string(page, "id"));
    const char *route = string(entry, "route");
    text(site, link, "href", *route == '/' ? route + 1 : route);
    return link;
}

/* This is a deferred rendering instruction, not HTML yet. For example,
 * {"$render":"rich-content","values":{"html":"<p>Hello</p>"}} tells renderer.c
 * which template to render later. set() transfers ownership of values. */
static cJSON *compose(struct site *site, const char *template, cJSON *values)
{
    cJSON *output = object(site);
    text(site, output, "$render", template);
    set(site, output, "values", values);
    return output;
}

static char *rich(struct site *site, const char *html)
{
    char *result = *site->error ? NULL : webeasy_rich(html, site->home_href);
    if (!result && !*site->error) fail(site, "Cannot prepare rich text.");
    return result;
}

static cJSON *aside_data(struct site *site, const cJSON *asides)
{
    cJSON *output = array(site);
    const cJSON *aside;
    cJSON_ArrayForEach(aside, asides) {
        if (*site->error) break;
        cJSON *fields = copy(site, aside), *values = object(site), *html_values = object(site);
        char *html = rich(site, string(aside, "content"));
        text(site, fields, "html", html ? html : "");
        text(site, html_values, "html", html ? html : "");
        free(html);
        text(site, values, "heading", string(aside, "heading"));
        set(site, values, "content", compose(site, "rich-content", html_values));
        set(site, fields, "content", compose(site, "aside-content", values));
        push(site, output, fields);
    }
    return output;
}

static cJSON *cover_data(struct site *site, const cJSON *record)
{
    const cJSON *image = cover(site, record);
    if (!image) return cJSON_CreateNull();
    cJSON *result = object(site);
    char *path = join(site, "media/", string(image, "filename"), "");
    text(site, result, "src", path ? path : "");
    free(path);
    text(site, result, "alt", string(record, "coverAlt"));
    return result;
}

static cJSON *record_data(struct site *site, const cJSON *record)
{
    cJSON *fields = copy(site, record), *used_ids = array(site), *sections = array(site);
    text(site, fields, "author", string(record, "author"));
    text(site, fields, "subtitle", string(record, "subtitle"));
    const cJSON *section;
    cJSON_ArrayForEach(section, get(record, "sections")) {
        if (*site->error) break;
        char *html = rich(site, string(section, "content"));
        cJSON *asides = aside_data(site, get(section, "asides"));
        cJSON *values = object(site), *html_values = object(site);
        text(site, values, "html", html ? html : "");
        text(site, html_values, "html", html ? html : "");
        free(html);
        flag(site, values, "hasAsides", cJSON_GetArraySize(asides) > 0);
        set(site, values, "asides", asides);
        set(site, values, "content", compose(site, "rich-content", html_values));
        cJSON *data = copy(site, section);
        const cJSON *item;
        cJSON_ArrayForEach(item, values) set(site, data, item->string, copy(site, item));
        char *heading_id = webeasy_heading_id(string(section, "heading"), used_ids);
        if (!heading_id) fail(site, "Cannot generate section heading ID.");
        text(site, data, "headingId", heading_id ? heading_id : "");
        free(heading_id);
        char *subheading = trim(site, string(section, "subheading"));
        text(site, data, "subheading", subheading ? subheading : "");
        free(subheading);
        cJSON *section_values = copy(site, data);
        set(site, section_values, "content", compose(site, "with-asides", values));
        set(site, data, "content", compose(site, "section-content", section_values));
        push(site, sections, data);
    }
    cJSON_Delete(used_ids);
    cJSON *children = array(site);
    const cJSON *child;
    cJSON_ArrayForEach(child, get(record, "children")) push(site, children, page_link(site, child));
    set(site, fields, "children", children);
    char *html = rich(site, string(record, "content"));
    text(site, fields, "html", html ? html : "");
    cJSON *content = object(site);
    text(site, content, "html", html ? html : "");
    free(html);
    set(site, content, "sections", copy(site, sections));
    set(site, fields, "sections", sections);
    cJSON *asides = aside_data(site, get(record, "asides"));
    flag(site, fields, "hasAsides", cJSON_GetArraySize(asides) > 0);
    set(site, fields, "asides", asides);
    set(site, fields, "cover", cover_data(site, record));
    flag(site, fields, "isLandingCover", !strcmp(string(record, "templateId"), "landing-page") && !cJSON_IsNull(get(fields, "cover")));
    set(site, fields, "content", compose(site, "main-content", content));
    /* Master uses the landing cover as its hero. The nested body gets a copy
     * without that cover, so the same image is not rendered twice. */
    cJSON *body_values = copy(site, fields);
    if (cJSON_IsTrue(get(fields, "isLandingCover"))) set(site, body_values, "cover", cJSON_CreateNull());
    cJSON *body = compose(site, "body", body_values);
    set(site, fields, "content", body);
    return fields;
}

static int active_page(const cJSON *entry, const char *id)
{
    if (!strcmp(string(get(entry, "record"), "id"), id)) return 1;
    const cJSON *ancestor;
    cJSON_ArrayForEach(ancestor, get(entry, "ancestors")) if (!strcmp(string(ancestor, "id"), id)) return 1;
    return 0;
}

static cJSON *subpages(struct site *site, const cJSON *pages, const char *prefix, const cJSON *entry, int depth)
{
    cJSON *output = array(site);
    if (depth > TREE_DEPTH || *site->error) return output;
    const cJSON *page;
    cJSON_ArrayForEach(page, pages) {
        char *route = join(site, prefix, string(page, "slug"), "/");
        if (!route) break;
        cJSON *children = subpages(site, get(page, "children"), route, entry, depth + 1);
        free(route);
        if (site->home && !strcmp(string(page, "id"), string(site->home, "id"))) {
            while (children && children->child) push(site, output, cJSON_DetachItemFromArray(children, 0));
            cJSON_Delete(children);
            continue;
        }
        cJSON *item = object(site);
        text(site, item, "id", string(page, "id"));
        text(site, item, "title", string(page, "title"));
        text(site, item, "menuTitle", string(page, "menuTitle"));
        const cJSON *page_route = route_entry(site, string(page, "id"));
        const char *href = string(page_route, "route");
        text(site, item, "href", *href == '/' ? href + 1 : href);
        flag(site, item, "active", active_page(entry, string(page, "id")));
        flag(site, item, "current", !strcmp(string(page, "id"), string(get(entry, "record"), "id")));
        flag(site, item, "hasChildren", cJSON_GetArraySize(children) > 0);
        set(site, item, "children", children);
        push(site, output, item);
    }
    return output;
}

static const char *page_template(struct site *site, const cJSON *page)
{
    const char *id = string(page, "templateId");
    if (strcmp(id, "page")) {
        const cJSON *template;
        int available = 0;
        cJSON_ArrayForEach(template, get(site->catalog, "templates"))
            if (!strcmp(string(template, "id"), id) && cJSON_IsTrue(get(template, "pageLayout"))) available = 1;
        if (!available) {
            char message[256]; snprintf(message, sizeof message, "Unavailable page layout: %s", id); fail(site, message);
        }
    }
    if (!cJSON_IsString(get(site->partials, id))) {
        char message[256]; snprintf(message, sizeof message, "Template is missing: %s", id); fail(site, message);
    }
    return id;
}

static cJSON *page_content(struct site *site, const cJSON *entry, int breadcrumbs)
{
    const cJSON *page = get(entry, "record"), *ancestors = get(entry, "ancestors");
    const cJSON *top = cJSON_GetArrayItem(ancestors, 0);
    if (!top) top = page;
    char *prefix = join(site, string(top, "slug"), "/", "");
    cJSON *items = subpages(site, get(top, "children"), prefix ? prefix : "", entry, 0);
    free(prefix);
    cJSON *values = object(site), *crumbs = array(site);
    text(site, values, "menuTitle", string(page, "menuTitle"));
    flag(site, values, "hasSubpages", cJSON_GetArraySize(items) > 0);
    set(site, values, "subpages", items);
    flag(site, values, "hasBreadcrumbs", breadcrumbs && cJSON_GetArraySize(ancestors) > 0);
    const cJSON *ancestor;
    cJSON_ArrayForEach(ancestor, ancestors) push(site, crumbs, page_link(site, ancestor));
    set(site, values, "breadcrumbs", crumbs);
    set(site, values, "content", compose(site, page_template(site, page), record_data(site, page)));
    return compose(site, "page-content", values);
}

static void document(struct site *site, const char *title, cJSON *content, const char *path, const char *current_id, cJSON *extra)
{
    const cJSON *current = current_id ? route_entry(site, current_id) : NULL;
    const cJSON *top = cJSON_GetArrayItem(get(current, "ancestors"), 0);
    const char *active_id = top ? string(top, "id") : current_id;
    int news_index = !strcmp(path, "news/index.html") || (!strcmp(path, "index.html") && !site->routes->child);
    int news_active = news_index || !strncmp(path, "news/", 5);
    cJSON *navigation = object(site), *menu = array(site);
    text(site, navigation, "homeHref", site->home_href);
    flag(site, navigation, "homeCurrent", current_id && site->home && !strcmp(current_id, string(site->home, "id")));
    flag(site, navigation, "newsActive", news_active);
    flag(site, navigation, "newsCurrent", news_index);
    const cJSON *page;
    cJSON_ArrayForEach(page, get(site->project, "pages")) {
        if (site->home && !strcmp(string(page, "id"), string(site->home, "id"))) continue;
        cJSON *link = page_link(site, page);
        flag(site, link, "active", active_id && !strcmp(string(page, "id"), active_id));
        flag(site, link, "current", current_id && !strcmp(string(page, "id"), current_id));
        push(site, menu, link);
    }
    set(site, navigation, "menu", menu);
    cJSON *values = extra ? extra : object(site);
    const cJSON *item;
    cJSON_ArrayForEach(item, navigation) set(site, values, item->string, copy(site, item));
    text(site, values, "title", title);
    const cJSON *record = get(current, "record");
    flag(site, values, "hasLandingCover", current && !strcmp(string(record, "templateId"), "landing-page") && cover(site, record));
    size_t depth = 0;
    for (const char *p = path; *p; p++) if (*p == '/') depth++;
    /* Files share root-level assets. news/date/slug/index.html needs "../../../"
     * as its base, while root index.html needs "./". The extra byte holds '\0',
     * the terminator C uses to mark the end of every string. */
    char *base = malloc(depth ? depth * 3 + 1 : 3);
    if (!base) fail(site, "Out of memory.");
    else {
        if (!depth) strcpy(base, "./");
        else { for (size_t i = 0; i < depth; i++) memcpy(base + i * 3, "../", 3); base[depth * 3] = 0; }
    }
    text(site, values, "baseHref", base ? base : "");
    free(base);
    set(site, values, "content", content);
    set(site, values, "navigation", compose(site, "site-header", navigation));
    cJSON *output = object(site);
    text(site, output, "path", path);
    text(site, output, "template", "master");
    set(site, output, "values", values);
    push(site, site->documents, output);
}

static int news_compare(const void *a, const void *b)
{
    /* qsort passes addresses of array entries, not the records themselves.
     * Entries are cJSON pointers, so cast to pointer-to-pointer and dereference
     * once to get each record. Reversed date comparison puts newest news first. */
    const cJSON *first = *(const cJSON *const *)a, *second = *(const cJSON *const *)b;
    int comparison = strcmp(string(second, "date"), string(first, "date"));
    return comparison ? comparison : strcmp(string(first, "slug"), string(second, "slug"));
}

static char *public_prefix(struct site *site, const cJSON *input)
{
    const cJSON *value = get(input, "publicPathPrefix");
    if (value && !cJSON_IsString(value)) fail(site, "Publishing paths must be text.");
    char *path = trim(site, cJSON_IsString(value) ? value->valuestring : "");
    if (!path) return NULL;
    size_t length = strlen(path);
    if (length > 512) fail(site, "Publishing paths are too long.");
    if (!*path) { free(path); return join(site, "./", "", ""); }
    if (!strcmp(path, "/")) return path;
    size_t start = path[0] == '/', end = length - (path[length - 1] == '/');
    int valid = end > start;
    for (size_t part = start, i = start; i <= end; i++) {
        if (i == end || path[i] == '/') {
            size_t size = i - part;
            if (!size || (size == 1 && path[part] == '.') || (size == 2 && !strncmp(path + part, "..", 2))) valid = 0;
            part = i + 1;
        } else if (!alpha((unsigned char)path[i]) && !digit((unsigned char)path[i]) &&
            path[i] != '.' && path[i] != '_' && path[i] != '-') valid = 0;
    }
    if (!valid) fail(site, "Use a public path such as /host/site/, without a hostname, query or parent segments.");
    path[end] = 0;
    char *result = join(site, "/", path + start, "/");
    free(path);
    return result;
}

/* Turn validated records into a render request: template sources, document
 * paths and the values each template receives. Templates/components become
 * named partials; pages and news become documents; master wraps each document. */
static cJSON *prepare(struct site *site, const cJSON *input)
{
    site->catalog = get(input, "catalog");
    site->partials = object(site);
    site->routes = array(site);
    site->documents = array(site);
    site->home_href = public_prefix(site, input);
    if (!site->home_href) return NULL;
    const cJSON *templates = get(site->catalog, "templates"), *components = get(site->catalog, "components");
    if (!cJSON_IsArray(templates) || !cJSON_IsArray(components)) fail(site, "Invalid template catalog.");
    for (int group = 0; group < 2; group++) {
        const cJSON *entry;
        cJSON_ArrayForEach(entry, (group ? components : templates)) {
            const char *id = required_text(site, entry, "id", 1);
            if (get(site->partials, id)) fail(site, "Template IDs must be unique across the catalog.");
            text(site, site->partials, id, required_text(site, entry, "source", 0));
        }
    }
    cJSON *ancestors = array(site);
    routes(site, get(site->project, "pages"), "/", ancestors, 0);
    cJSON_Delete(ancestors);
    site->home_entry = route_entry(site, string(site->project, "homePageId"));
    if (!site->home_entry) site->home_entry = cJSON_GetArrayItem(site->routes, 0);
    site->home = get(site->home_entry, "record");
    const cJSON *entry;
    cJSON_ArrayForEach(entry, site->routes) {
        if (*site->error) break;
        const cJSON *page = get(entry, "record");
        char *file = join(site, string(entry, "route") + 1, "index.html", "");
        cJSON *extra = object(site);
        set(site, extra, "page", record_data(site, page));
        if (file) document(site, string(page, "title"), page_content(site, entry, 1), file, string(page, "id"), extra);
        else cJSON_Delete(extra);
        free(file);
    }
    int count = cJSON_GetArraySize(get(site->project, "news"));
    /* Allocate an array of pointers, not copies of all the news records.
     * sizeof *sorted is the size of one pointer. Sorting this temporary array
     * leaves the saved project's news order unchanged. */
    const cJSON **sorted = count ? malloc((size_t)count * sizeof *sorted) : NULL;
    if (count && !sorted) fail(site, "Out of memory.");
    if (sorted) {
        int i = 0;
        cJSON_ArrayForEach(entry, get(site->project, "news")) sorted[i++] = entry;
        qsort(sorted, (size_t)count, sizeof *sorted, news_compare);
    }
    cJSON *all_items = array(site), *items = array(site);
    for (int i = 0; sorted && i < count && !*site->error; i++) {
        const cJSON *news = sorted[i];
        char *route = join(site, string(news, "date"), "/", string(news, "slug"));
        char *file = route ? join(site, "news/", route, "/index.html") : NULL;
        cJSON *article = record_data(site, news), *extra = object(site);
        set(site, extra, "news", copy(site, article));
        if (file) document(site, string(news, "title"), compose(site, "news", article), file, NULL, extra);
        else { cJSON_Delete(article); cJSON_Delete(extra); }
        cJSON *summary = object(site);
        static const char *fields[] = {"id","title","date","slug","shortContent"};
        for (size_t f = 0; f < sizeof fields / sizeof *fields; f++) text(site, summary, fields[f], string(news, fields[f]));
        char *html = rich(site, string(news, "shortContent"));
        text(site, summary, "html", html ? html : "");
        free(html);
        set(site, summary, "cover", cover_data(site, news));
        char *href = route ? join(site, "news/", route, "/") : NULL;
        text(site, summary, "href", href ? href : "");
        free(href); free(file); free(route);
        if (i < 3) push(site, items, copy(site, summary));
        push(site, all_items, summary);
    }
    free(sorted);
    cJSON *listing_values = object(site);
    set(site, listing_values, "items", items);
    set(site, listing_values, "allItems", all_items);
    cJSON *listing_data = copy(site, listing_values);
    set(site, listing_data, "content", compose(site, "news-list", copy(site, listing_values)));
    cJSON *listing = compose(site, "news-index", listing_data);
    document(site, "News", copy(site, listing), "news/index.html", NULL, copy(site, listing_values));
    cJSON *extra = site->home ? object(site) : copy(site, listing_values);
    if (site->home) set(site, extra, "page", record_data(site, site->home));
    document(site, site->home ? string(site->home, "title") : "News",
        site->home ? page_content(site, site->home_entry, 0) : copy(site, listing), "index.html",
        site->home ? string(site->home, "id") : NULL, extra);
    cJSON_Delete(listing); cJSON_Delete(listing_values);
    cJSON *request = object(site);
    set(site, request, "partials", copy(site, site->partials));
    set(site, request, "documents", copy(site, site->documents));
    cJSON *raw_slots = array(site);
    for (int i = 0; i < 3; i++) {
        static const char *slots[] = {"content","navigation","html"};
        push(site, raw_slots, cJSON_CreateString(slots[i]));
    }
    set(site, request, "rawSlots", raw_slots);
    return request;
}

/* Walk normalized content, not every managed media file. Publishing needs only
 * images/videos actually referenced by rich text or covers. paths is the shared
 * result array passed through recursion, so repeated references appear once. */
static void referenced_media(struct site *site, const cJSON *record, cJSON *paths, int depth)
{
    if (*site->error) return;
    if (depth > TREE_DEPTH * 3) { fail(site, "Content tree is too deep."); return; }
    if (cJSON_IsArray(record)) {
        const cJSON *item;
        cJSON_ArrayForEach(item, record) referenced_media(site, item, paths, depth + 1);
    } else if (cJSON_IsObject(record)) {
        const cJSON *image = cover(site, record);
        if (image) {
            char *path = join(site, "media/", string(image, "filename"), "");
            if (path && !contains(paths, path)) push(site, paths, cJSON_CreateString(path));
            free(path);
        }
        const cJSON *field;
        cJSON_ArrayForEach(field, record) {
            if ((!strcmp(field->string, "content") || !strcmp(field->string, "shortContent")) && cJSON_IsString(field)) {
                if (webeasy_collect_media(field->valuestring, paths) < 0) fail(site, "Cannot collect referenced media.");
            } else if (cJSON_IsArray(field)) referenced_media(site, field, paths, depth + 1);
        }
    }
}

/* cJSON stores strings as C strings, so an embedded JSON NUL would silently
 * truncate user text. Reject it before parsing. Skipping escaped backslashes
 * still permits literal text such as "\\u0000" in an article or code example. */
static int nul_escape(const char *request)
{
    for (const char *p = request; *p; p++) {
        if (*p != '\\') continue;
        if (!strncmp(p + 1, "u0000", 5)) return 1;
        if (p[1]) p++;
    }
    return 0;
}

/* All three public APIs share this lifetime:
 *   0: project JSON -> normalized project
 *   1: {project,catalog,...} -> normalized project + deferred render request
 *   2: {project,catalog,...} -> normalized project + rendered HTML files
 * The returned JSON string is heap memory; the caller releases it with
 * webeasy_free(). Temporary JSON trees stay here and are always cleaned up. */
static char *operation(const char *request, int compile)
{
    /* {0} initializes the entire struct: NULL pointers and an empty error.
     * Here site is a struct value, so use site.error; &site passes its address
     * to helpers, which use site->error through their pointer parameter. */
    struct site site = {0};
    cJSON *input = NULL, *output = NULL;
    if (!request || strlen(request) > SITE_LIMIT) fail(&site, "Content request is missing or too large.");
    else if (nul_escape(request)) fail(&site, "Content cannot contain embedded NUL characters.");
    else input = cJSON_ParseWithOpts(request, NULL, 1);
    site.ids = array(&site);
    if (!input && !*site.error) fail(&site, "Invalid content request.");
    if (!*site.error) normalize(&site, compile ? get(input, "project") : input);
    output = object(&site);
    if (!*site.error) set(&site, output, "project", copy(&site, site.project));
    if (compile && !*site.error) {
        cJSON *render_request = prepare(&site, input);
        if (compile == 2 && !*site.error) {
            /* renderer.c accepts/returns JSON text, just like the native bridge.
             * Printing allocates a string; parsing creates an independent tree.
             * Copy the files out before freeing both temporary representations. */
            char *serialized = cJSON_PrintUnformatted(render_request);
            char *rendered = serialized ? webeasy_render(serialized) : NULL;
            cJSON *result = rendered ? cJSON_ParseWithOpts(rendered, NULL, 1) : NULL;
            if (!result) fail(&site, "Cannot render website.");
            else if (cJSON_IsString(get(result, "error"))) fail(&site, string(result, "error"));
            else if (!cJSON_IsArray(get(result, "files"))) fail(&site, "Invalid renderer response.");
            else set(&site, output, "files", copy(&site, get(result, "files")));
            free(serialized); webeasy_free(rendered); cJSON_Delete(result);
        } else {
            set(&site, output, "renderRequest", copy(&site, render_request));
            set(&site, output, "files", array(&site));
        }
        cJSON_Delete(render_request);
        cJSON *paths = array(&site);
        referenced_media(&site, site.project, paths, 0);
        set(&site, output, "referencedMedia", paths);
    }
    if (*site.error) {
        /* Never return partially generated files as if generation succeeded. */
        cJSON_Delete(output);
        output = cJSON_CreateObject();
        if (output) cJSON_AddStringToObject(output, "error", site.error);
    }
    char *result = output ? cJSON_PrintUnformatted(output) : NULL;
    /* Serialization copied the output into result, so deleting these trees
     * cannot invalidate it. free(NULL) is safe on paths that failed early.
     * If even serialization cannot allocate memory, result is NULL. */
    cJSON_Delete(input); cJSON_Delete(output); cJSON_Delete(site.ids);
    cJSON_Delete(site.project); cJSON_Delete(site.partials); cJSON_Delete(site.routes); cJSON_Delete(site.documents);
    free(site.home_href);
    return result;
}

char *webeasy_normalize(const char *project) { return operation(project, 0); }
char *webeasy_prepare(const char *input) { return operation(input, 1); }
char *webeasy_compile(const char *input) { return operation(input, 2); }
