#ifndef WEBEASY_HTML_H
#define WEBEASY_HTML_H

#include "vendor/cjson/cJSON.h"

#ifdef __cplusplus
extern "C" {
#endif

/* All returned strings belong to the caller; release them with free() or
 * webeasy_free(). NULL means allocation, invalid UTF-8, or resource failure.
 * Malformed HTML is recovered by Lexbor's HTML5 parser, never by a fallback.
 * Limits: 2 MiB input, 16 MiB output, 100,000 nodes/tokens, depth 128;
 * URL/media strings are limited to 64 KiB and ID arrays to 100,000 entries. */
char *webeasy_sanitize(const char *html, int paragraphs_only);
int webeasy_safe_href(const char *href);
int webeasy_local_media(const char *path);

/* Input must already have been sanitized. Only anchor destinations '/', and
 * '[./]index.html', optionally followed by query/hash, are rewritten.
 * './' is accepted as the generated home destination (not as stored href).
 * No other attributes or literal text are rewritten. */
char *webeasy_rich(const char *sanitized_html, const char *home_href);

/* used_ids and paths must be cJSON arrays. IDs are NFKD Prosango ASCII slugs
 * of at most 25 characters, made unique by numbered suffixes.
 * Array additions are owned by cJSON. On failure the input arrays are unchanged.
 * Integer APIs return 1/0 for accepted/rejected, or -1 on resource failure;
 * collect_media returns 0 on success and -1 on failure. */
char *webeasy_heading_id(const char *text, cJSON *used_ids);
int webeasy_collect_media(const char *html, cJSON *paths);

#ifdef __cplusplus
}
#endif
#endif
