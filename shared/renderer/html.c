#include "html.h"

#include "lexbor/html/html.h"
#include "lexbor/html/parser.h"
#include "lexbor/html/serialize.h"
#include "lexbor/dom/interfaces/character_data.h"
#include "lexbor/url/url.h"
#include "utf8proc.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define HTML_LIMIT (2u * 1024u * 1024u)
#define OUTPUT_LIMIT (16u * 1024u * 1024u)
#define VALUE_LIMIT (64u * 1024u)
#define NODE_LIMIT 100000u
#define DEPTH_LIMIT 128u
#define ID_LIMIT 25u

typedef lxb_dom_node_t Node;

struct dom {
    lxb_html_document_t *document;
    lxb_html_parser_t *parser;
    Node *root;
    lxb_html_tokenizer_token_f original_callback;
    void *original_context;
    size_t tokens;
    size_t nodes;
    int failed;
};

struct output {
    char *data;
    size_t length;
    size_t capacity;
};

struct strings {
    const char **slots;
    size_t capacity;
};

static size_t bounded_length(const char *text, size_t limit)
{
    size_t size = 0;
    if (!text) return 0;
    while (size <= limit && text[size]) size++;
    return size;
}

static char *copy_bytes(const unsigned char *text, size_t length)
{
    char *copy = malloc(length + 1);
    if (copy) {
        if (length) memcpy(copy, text, length);
        copy[length] = 0;
    }
    return copy;
}

static int valid_utf8(const unsigned char *text, size_t length)
{
    for (size_t offset = 0; offset < length;) {
        utf8proc_int32_t point;
        utf8proc_ssize_t size = utf8proc_iterate(text + offset,
                                               (utf8proc_ssize_t)(length - offset), &point);
        if (size <= 0) return 0;
        offset += (size_t)size;
    }
    return 1;
}

/* ECMAScript trim's whitespace set, rather than locale-dependent isspace().
 * This also treats a caption containing only NBSP as empty. */
static int whitespace(utf8proc_int32_t point)
{
    return (point >= 9 && point <= 13) || point == 32 || point == 0xa0 ||
           point == 0x1680 || (point >= 0x2000 && point <= 0x200a) ||
           point == 0x2028 || point == 0x2029 || point == 0x202f ||
           point == 0x205f || point == 0x3000 || point == 0xfeff;
}

static int has_text(const unsigned char *text, size_t length)
{
    for (size_t offset = 0; offset < length;) {
        utf8proc_int32_t point;
        utf8proc_ssize_t size = utf8proc_iterate(text + offset,
                                               (utf8proc_ssize_t)(length - offset), &point);
        if (size <= 0 || !whitespace(point)) return 1;
        offset += (size_t)size;
    }
    return 0;
}

static char *trim_value(const unsigned char *text, size_t length)
{
    size_t first = length, last = length;
    for (size_t offset = 0; offset < length;) {
        utf8proc_int32_t point;
        utf8proc_ssize_t size = utf8proc_iterate(text + offset,
                                               (utf8proc_ssize_t)(length - offset), &point);
        if (size <= 0) return NULL;
        if (!whitespace(point)) {
            if (first == length) first = offset;
            last = offset + (size_t)size;
        }
        offset += (size_t)size;
    }
    return copy_bytes(text ? text + first : NULL, last - first);
}

static int ascii_prefix(const char *text, const char *prefix)
{
    while (*prefix) {
        unsigned char c = (unsigned char)*text++;
        if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
        if (c != (unsigned char)*prefix++) return 0;
    }
    return 1;
}

static int hex_digit(unsigned char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static int dot_segments(const unsigned char *path, size_t length)
{
    for (size_t start = 0, end = 0; end <= length; end++) {
        if (end != length && path[end] != '/') continue;
        size_t count = end - start;
        if ((count == 1 || count == 2) && path[start] == '.' &&
            (count == 1 || path[start + 1] == '.')) return 1;
        start = end + 1;
    }
    return 0;
}

int webeasy_local_media(const char *path)
{
    size_t length = bounded_length(path, VALUE_LIMIT), prefix;
    if (length > VALUE_LIMIT) return -1;
    if (!length) return 0;
    if (!strncmp(path, "assets/", 7)) prefix = 7;
    else if (!strncmp(path, "media/", 6)) prefix = 6;
    else return 0;
    if (length <= prefix) return 0;
    for (size_t index = prefix; index < length; index++) {
        unsigned char c = (unsigned char)path[index];
        int alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9');
        if (!alnum && (index == prefix || (c != '.' && c != '_' && c != '/' && c != '-')))
            return 0;
    }
    return !dot_segments((const unsigned char *)path, length);
}

int webeasy_safe_href(const char *href)
{
    size_t length = bounded_length(href, VALUE_LIMIT);
    if (length > VALUE_LIMIT) return -1;
    if (!length) return 0;
    if (!valid_utf8((const unsigned char *)href, length)) return 0;
    for (size_t index = 0; index < length; index++) {
        unsigned char c = (unsigned char)href[index];
        if (c <= 32 || c == 127 || c == '\\') return 0;
    }
    int http = ascii_prefix(href, "http://") || ascii_prefix(href, "https://");
    int contact = ascii_prefix(href, "mailto:") || ascii_prefix(href, "tel:");
    size_t path_length = strcspn(href, "?#");
    if (!http && !contact) {
        if (!strncmp(href, "//", 2) || memchr(href, ':', path_length)) return 0;
        /* decodeURIComponent applies only to relative paths. Invalid escapes or
         * UTF-8 are rejected; decoded slashes must not conceal dot segments. */
        unsigned char *decoded = malloc(path_length + 1);
        if (!decoded) return -1;
        size_t count = 0;
        for (size_t index = 0; index < path_length; index++) {
            unsigned char c = (unsigned char)href[index];
            if (c == '%') {
                if (index + 2 >= path_length || hex_digit(href[index + 1]) < 0 ||
                    hex_digit(href[index + 2]) < 0) {
                    free(decoded);
                    return 0;
                }
                c = (unsigned char)(hex_digit(href[index + 1]) * 16 + hex_digit(href[index + 2]));
                index += 2;
            }
            decoded[count++] = c;
        }
        int accepted = valid_utf8(decoded, count) && !dot_segments(decoded, count);
        free(decoded);
        if (!accepted) return 0;
    } else if (contact && !strchr(href, ':')[1]) return 0;

    /* Lexbor implements WHATWG URL parsing, including invalid ports, IPv6,
     * international domains and browser-compatible relative resolution. */
    lxb_url_parser_t *parser = lxb_url_parser_create();
    if (!parser) return -1;
    int accepted = -1;
    if (lxb_url_parser_init(parser, NULL) == LXB_STATUS_OK) {
        const unsigned char base_text[] = "https://local.invalid/";
        lxb_status_t status = lxb_url_parse_basic(parser, NULL, NULL, base_text,
                    sizeof(base_text) - 1, LXB_URL_STATE__UNDEF, LXB_ENCODING_AUTO);
        lxb_url_t *base = lxb_url_get(parser);
        if (status == LXB_STATUS_OK && base) {
            lxb_url_parser_clean(parser);
            status = lxb_url_parse_basic(parser, NULL, base, (const unsigned char *)href,
                                        length, LXB_URL_STATE__UNDEF, LXB_ENCODING_AUTO);
            lxb_url_t *url = lxb_url_get(parser);
            accepted = status == LXB_STATUS_ERROR_MEMORY_ALLOCATION ? -1 :
                       status == LXB_STATUS_OK && url != NULL;
            if (http && url)
                accepted = url->host.type != LXB_URL_HOST_TYPE_EMPTY &&
                           url->host.type != LXB_URL_HOST_TYPE__UNDEF;
        }
    }
    lxb_url_parser_memory_destroy(parser);
    lxb_url_parser_destroy(parser, true);
    return accepted;
}

/* Token interception delegates all parsing to Lexbor. It only stops excessive
 * work, before a malicious nesting/formatting stack can become unbounded. */
static lxb_html_token_t *guard_token(lxb_html_tokenizer_t *tokenizer,
                                    lxb_html_token_t *token, void *context)
{
    struct dom *dom = context;
    lxb_html_tree_t *tree = dom->parser->tree;
    if (++dom->tokens > NODE_LIMIT || tree->open_elements->length > DEPTH_LIMIT ||
        tree->active_formatting->length > DEPTH_LIMIT ||
        tree->template_insertion_modes->length > DEPTH_LIMIT) {
        dom->failed = 1;
        tokenizer->status = LXB_STATUS_ABORTED;
        return NULL;
    }
    return dom->original_callback(tokenizer, token, dom->original_context);
}

static int count_tree(Node *root, size_t depth, size_t *count)
{
    if (depth > DEPTH_LIMIT || ++*count > NODE_LIMIT) return 0;
    for (Node *child = root->first_child; child; child = child->next)
        if (!count_tree(child, depth + 1, count)) return 0;
    return 1;
}

static void destroy_dom(struct dom *dom)
{
    /* Fragment parsing creates a temporary document owned by the parser; the
     * returned nodes use the original document's arena. Destroy both owners. */
    if (dom->parser) lxb_html_parser_destroy(dom->parser);
    if (dom->document) lxb_html_document_destroy(dom->document);
    memset(dom, 0, sizeof *dom);
}

static int parse_dom(struct dom *dom, const char *html)
{
    memset(dom, 0, sizeof *dom);
    size_t length = bounded_length(html, HTML_LIMIT);
    if (!html || length > HTML_LIMIT || !valid_utf8((const unsigned char *)html, length))
        return 0;
    dom->document = lxb_html_document_create();
    dom->parser = lxb_html_parser_create();
    if (!dom->document || !dom->parser ||
        lxb_html_parser_init(dom->parser) != LXB_STATUS_OK ||
        lxb_html_parse_fragment_chunk_begin(dom->parser, dom->document,
                                            LXB_TAG_DIV, LXB_NS_HTML) != LXB_STATUS_OK)
        goto failure;
    lxb_html_tokenizer_t *tokenizer = dom->parser->tkz;
    dom->original_callback = tokenizer->callback_token_done;
    dom->original_context = tokenizer->callback_token_ctx;
    lxb_html_tokenizer_callback_token_done_set(tokenizer, guard_token, dom);
    if (lxb_html_parse_fragment_chunk_process(dom->parser, (const unsigned char *)html,
                                             length) != LXB_STATUS_OK)
        goto failure;
    dom->root = lxb_html_parse_fragment_chunk_end(dom->parser);
    if (!dom->root || dom->failed || !count_tree(dom->root, 0, &dom->nodes))
        goto failure;
    return 1;
failure:
    destroy_dom(dom);
    return 0;
}

static Node *element(struct dom *dom, const char *name)
{
    if (++dom->nodes > NODE_LIMIT) return NULL;
    return lxb_dom_interface_node(lxb_html_document_create_element(dom->document,
                                    (const unsigned char *)name, strlen(name), NULL));
}

static int set_attr(Node *node, const char *name, const char *value)
{
    return lxb_dom_element_set_attribute(lxb_dom_interface_element(node),
                (const unsigned char *)name, strlen(name),
                (const unsigned char *)value, strlen(value)) != NULL;
}

static const unsigned char *get_attr(Node *node, const char *name, size_t *length)
{
    return lxb_dom_element_get_attribute(lxb_dom_interface_element(node),
                        (const unsigned char *)name, strlen(name), length);
}

static void move_child(Node *parent, Node *child)
{
    lxb_dom_node_remove(child);
    lxb_dom_node_insert_child(parent, child);
}

static void move_before(Node *before, Node *child)
{
    lxb_dom_node_remove(child);
    lxb_dom_node_insert_before(before, child);
}

static void move_children(Node *parent, Node *source)
{
    while (source->first_child) move_child(parent, source->first_child);
}

static int inline_tag(uintptr_t tag)
{
    return tag == LXB_TAG_BR || tag == LXB_TAG_B || tag == LXB_TAG_STRONG ||
           tag == LXB_TAG_I || tag == LXB_TAG_EM || tag == LXB_TAG_S ||
           tag == LXB_TAG_STRIKE || tag == LXB_TAG_DEL || tag == LXB_TAG_U || tag == LXB_TAG_A;
}

static int block_tag(uintptr_t tag)
{
    return tag == LXB_TAG_P || tag == LXB_TAG_DIV ||
           (tag >= LXB_TAG_H1 && tag <= LXB_TAG_H6) || tag == LXB_TAG_UL ||
           tag == LXB_TAG_OL || tag == LXB_TAG_LI || tag == LXB_TAG_BLOCKQUOTE ||
           tag == LXB_TAG_FIGURE || tag == LXB_TAG_FIGCAPTION;
}

static int media_tag(uintptr_t tag)
{
    return tag == LXB_TAG_IMG || tag == LXB_TAG_VIDEO || tag == LXB_TAG_SOURCE;
}

static int allowed_tag(uintptr_t tag, int paragraphs_only)
{
    return inline_tag(tag) || (block_tag(tag) && (tag != LXB_TAG_DIV || paragraphs_only)) ||
           media_tag(tag);
}

static int drop_content(Node *node)
{
    /* Foreign SVG/MathML subtrees and raw-text/template containers are removed
     * altogether. Other unsupported HTML wrappers keep sanitized children. */
    if (node->ns != LXB_NS_HTML) return 1;
    switch (node->local_name) {
        case LXB_TAG_SCRIPT: case LXB_TAG_STYLE: case LXB_TAG_IFRAME:
        case LXB_TAG_TEMPLATE: case LXB_TAG_HEAD: case LXB_TAG_TITLE:
        case LXB_TAG_NOEMBED: case LXB_TAG_NOFRAMES: case LXB_TAG_NOSCRIPT:
        case LXB_TAG_PLAINTEXT: case LXB_TAG_XMP: case LXB_TAG_AUDIO:
        case LXB_TAG_COLGROUP: case LXB_TAG_THEAD:
            return 1;
        default: return 0;
    }
}

static int valid_attribute(uintptr_t tag, const char *name, const char *value)
{
    int permitted =
        (tag == LXB_TAG_A && (!strcmp(name, "href") || !strcmp(name, "title"))) ||
        (tag == LXB_TAG_IMG && (!strcmp(name, "src") || !strcmp(name, "alt") ||
            !strcmp(name, "width") || !strcmp(name, "height") || !strcmp(name, "loading"))) ||
        (tag == LXB_TAG_VIDEO && (!strcmp(name, "src") || !strcmp(name, "poster") ||
            !strcmp(name, "controls") || !strcmp(name, "playsinline") ||
            !strcmp(name, "preload") || !strcmp(name, "width") || !strcmp(name, "height"))) ||
        (tag == LXB_TAG_SOURCE && (!strcmp(name, "src") || !strcmp(name, "type")));
    if (!permitted) return 0;
    if (!strcmp(name, "href")) return webeasy_safe_href(value);
    if (!strcmp(name, "src") || !strcmp(name, "poster")) return webeasy_local_media(value);
    if (!strcmp(name, "width") || !strcmp(name, "height")) {
        if (*value < '1' || *value > '9') return 0;
        for (const char *c = value + 1; *c; c++)
            if (*c < '0' || *c > '9') return 0;
    }
    if (!strcmp(name, "preload"))
        return !strcmp(value, "none") || !strcmp(value, "metadata") || !strcmp(value, "auto");
    if (!strcmp(name, "loading")) return !strcmp(value, "lazy") || !strcmp(value, "eager");
    if (!strcmp(name, "type"))
        return !strcmp(value, "video/mp4") || !strcmp(value, "video/webm") ||
               !strcmp(value, "video/quicktime") || !strcmp(value, "video/ogg");
    return 1;
}

static int copy_attributes(Node *destination, Node *source)
{
    for (lxb_dom_attr_t *attr = lxb_dom_element_first_attribute(lxb_dom_interface_element(source));
         attr; attr = attr->next) {
        /* Lexbor labels parsed ordinary attributes with the element's HTML
         * namespace; programmatically created attributes use the null one.
         * Foreign/prefixed names can never enter our HTML allowlist. */
        if ((attr->node.ns != LXB_NS__UNDEF && attr->node.ns != LXB_NS_HTML) ||
            attr->node.prefix != 0) continue;
        size_t name_length = 0, value_length = 0;
        const unsigned char *name_data = lxb_dom_attr_local_name(attr, &name_length);
        const unsigned char *value_data = lxb_dom_attr_value(attr, &value_length);
        if (!name_data || name_length > VALUE_LIMIT || value_length > VALUE_LIMIT) return 0;
        char *name = copy_bytes(name_data, name_length);
        char *value = trim_value(value_data, value_length);
        if (!name || !value) { free(name); free(value); return 0; }
        int valid = valid_attribute(destination->local_name, name, value);
        int ok = valid >= 0 && (!valid || set_attr(destination, name, value));
        free(name);
        free(value);
        if (!ok) return 0;
    }
    return 1;
}

/* Rebuild a clean DOM instead of editing a potentially hostile tree in place:
 * nothing reaches serialization unless both its tag and attributes passed the
 * explicit allowlist. HTML5 recovery and entity decoding remain Lexbor's job. */
static int clean_children(struct dom *dom, Node *destination, Node *source,
                          int paragraphs_only, size_t depth)
{
    if (depth > DEPTH_LIMIT) return 0;
    for (Node *child = source->first_child; child; child = child->next) {
        if (child->type == LXB_DOM_NODE_TYPE_TEXT) {
            if (++dom->nodes > NODE_LIMIT) return 0;
            Node *copy = lxb_dom_node_clone(child, false);
            if (!copy) return 0;
            lxb_dom_node_insert_child(destination, copy);
        } else if (child->type == LXB_DOM_NODE_TYPE_ELEMENT && !drop_content(child)) {
            if (!allowed_tag(child->local_name, paragraphs_only)) {
                if (!clean_children(dom, destination, child, paragraphs_only, depth + 1)) return 0;
                continue;
            }
            size_t length;
            const unsigned char *name = lxb_dom_element_local_name(lxb_dom_interface_element(child), &length);
            const char *replacement = child->local_name == LXB_TAG_H1 ? "h2" :
                                      child->local_name == LXB_TAG_B ? "strong" : (const char *)name;
            char *tag = copy_bytes((const unsigned char *)replacement,
                                   replacement == (const char *)name ? length : strlen(replacement));
            if (!tag) return 0;
            Node *copy = element(dom, tag);
            free(tag);
            if (!copy || !copy_attributes(copy, child) ||
                !clean_children(dom, copy, child, paragraphs_only, depth + 1)) return 0;
            lxb_dom_node_insert_child(destination, copy);
        }
    }
    return 1;
}

static Node *nearest_figure(Node *node)
{
    for (Node *parent = node->parent; parent; parent = parent->parent)
        if (parent->local_name == LXB_TAG_FIGURE) return parent;
    return NULL;
}

static int contains(Node *node, int mode)
{
    for (Node *child = node->first_child; child; child = child->next) {
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT &&
            (mode == 0 ? block_tag(child->local_name) :
             mode == 1 ? child->local_name == LXB_TAG_IMG || child->local_name == LXB_TAG_VIDEO ||
                         child->local_name == LXB_TAG_FIGURE :
             child->local_name == LXB_TAG_BR)) return 1;
        if (contains(child, mode)) return 1;
    }
    return 0;
}

static int text_nonempty(Node *node)
{
    if (node->type == LXB_DOM_NODE_TYPE_TEXT) {
        lexbor_str_t *text = &lxb_dom_interface_character_data(node)->data;
        return has_text(text->data, text->length);
    }
    for (Node *child = node->first_child; child; child = child->next)
        if (text_nonempty(child)) return 1;
    return 0;
}

static int normalize_media(struct dom *dom, Node *root)
{
    for (Node *node = root->first_child; node;) {
        Node *next = node->next;
        if (node->local_name == LXB_TAG_IMG || node->local_name == LXB_TAG_VIDEO) {
            if (node->local_name == LXB_TAG_IMG && !get_attr(node, "src", NULL)) {
                lxb_dom_node_remove(node);
                node = next;
                continue;
            }
            if (!nearest_figure(node)) {
                Node *figure = element(dom, "figure");
                if (!figure) return 0;
                lxb_dom_node_insert_before(node, figure);
                move_child(figure, node);
            }
            if (node->local_name == LXB_TAG_IMG) {
                if ((!get_attr(node, "alt", NULL) && !set_attr(node, "alt", "")) ||
                    (!get_attr(node, "loading", NULL) && !set_attr(node, "loading", "lazy")))
                    return 0;
            } else if (!set_attr(node, "controls", "") || !set_attr(node, "playsinline", "") ||
                (!get_attr(node, "preload", NULL) && !set_attr(node, "preload", "metadata"))) return 0;
        } else if (node->local_name == LXB_TAG_SOURCE &&
                   (node->parent->local_name != LXB_TAG_VIDEO || !get_attr(node, "src", NULL))) {
            lxb_dom_node_remove(node);
            node = next;
            continue;
        }
        if (!normalize_media(dom, node)) return 0;
        node = next;
    }
    return 1;
}

static void lift_media(Node *root, Node *figure, Node *before)
{
    for (Node *child = root->first_child; child;) {
        Node *next = child->next;
        if ((child->local_name == LXB_TAG_IMG || child->local_name == LXB_TAG_VIDEO) &&
            nearest_figure(child) == figure) move_before(before, child);
        else lift_media(child, figure, before);
        child = next;
    }
}

/* Work inside-out: each figure gets one caption, preserving nested figures.
 * Media inside text-bearing caption wrappers is lifted before that wrapper,
 * just as the existing editor sanitizer does. Whitespace-only captions vanish. */
static int normalize_figures(struct dom *dom, Node *root)
{
    for (Node *child = root->first_child; child; child = child->next)
        if (!normalize_figures(dom, child)) return 0;
    if (root->local_name != LXB_TAG_FIGURE) return 1;
    if (!set_attr(root, "class", "stack")) return 0;
    Node *caption = element(dom, "figcaption");
    if (!caption) return 0;
    for (Node *child = root->first_child; child;) {
        Node *next = child->next;
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT) {
            if (child->local_name == LXB_TAG_IMG || child->local_name == LXB_TAG_VIDEO ||
                child->local_name == LXB_TAG_FIGURE) { child = next; continue; }
            if (contains(child, 1)) {
                if (!text_nonempty(child)) { child = next; continue; }
                lift_media(child, root, child);
            }
            if (child->local_name == LXB_TAG_FIGCAPTION) {
                move_children(caption, child);
                lxb_dom_node_remove(child);
                child = next;
                continue;
            }
        }
        move_child(caption, child);
        child = next;
    }
    if (text_nonempty(caption)) lxb_dom_node_insert_child(root, caption);
    return 1;
}

static int wrap_paragraphs(struct dom *dom, Node *root)
{
    Node *paragraph = NULL;
    for (Node *node = root->first_child; node;) {
        Node *next = node->next;
        if (node->type == LXB_DOM_NODE_TYPE_TEXT ||
            (node->type == LXB_DOM_NODE_TYPE_ELEMENT && inline_tag(node->local_name) &&
             !contains(node, 0))) {
            if (!paragraph) {
                if (node->type == LXB_DOM_NODE_TYPE_TEXT && !text_nonempty(node)) {
                    node = next;
                    continue;
                }
                paragraph = element(dom, "p");
                if (!paragraph) return 0;
                lxb_dom_node_insert_before(node, paragraph);
            }
            move_child(paragraph, node);
        } else paragraph = NULL;
        node = next;
    }
    return 1;
}

static void remove_media(Node *root)
{
    for (Node *child = root->first_child; child;) {
        Node *next = child->next;
        if (child->type == LXB_DOM_NODE_TYPE_ELEMENT && media_tag(child->local_name))
            lxb_dom_node_remove(child);
        else remove_media(child);
        child = next;
    }
}

static int flush_paragraph(struct dom *dom, Node *output, Node **paragraph)
{
    if (text_nonempty(*paragraph) || contains(*paragraph, 2))
        lxb_dom_node_insert_child(output, *paragraph);
    *paragraph = element(dom, "p");
    return *paragraph != NULL;
}

/* Short content traverses blocks as paragraph boundaries while keeping inline
 * subtrees intact. Moving nodes avoids a second deep clone; media is discarded
 * even when embedded inside an otherwise allowed inline element. */
static int paragraphs_visit(struct dom *dom, Node *root, Node *output, Node **paragraph)
{
    for (Node *child = root->first_child; child;) {
        Node *next = child->next;
        if (child->type == LXB_DOM_NODE_TYPE_TEXT) move_child(*paragraph, child);
        else if (child->type == LXB_DOM_NODE_TYPE_ELEMENT && !media_tag(child->local_name)) {
            if (inline_tag(child->local_name) && !contains(child, 0)) {
                remove_media(child);
                move_child(*paragraph, child);
            } else if (!flush_paragraph(dom, output, paragraph) ||
                       !paragraphs_visit(dom, child, output, paragraph) ||
                       !flush_paragraph(dom, output, paragraph)) return 0;
        }
        child = next;
    }
    return 1;
}

static lxb_status_t append_output(const unsigned char *text, size_t length, void *context)
{
    struct output *output = context;
    if (length > OUTPUT_LIMIT - output->length) return LXB_STATUS_ERROR_OVERFLOW;
    size_t needed = output->length + length + 1;
    if (needed > output->capacity) {
        size_t capacity = output->capacity ? output->capacity : 1024;
        while (capacity < needed) capacity *= 2;
        char *data = realloc(output->data, capacity);
        if (!data) return LXB_STATUS_ERROR_MEMORY_ALLOCATION;
        output->data = data;
        output->capacity = capacity;
    }
    if (length) memcpy(output->data + output->length, text, length);
    output->length += length;
    output->data[output->length] = 0;
    return LXB_STATUS_OK;
}

static char *serialize(Node *root)
{
    struct output output = {0};
    size_t count = 0;
    if (!count_tree(root, 0, &count) ||
        lxb_html_serialize_deep_cb(root, append_output, &output) != LXB_STATUS_OK) {
        free(output.data);
        return NULL;
    }
    return output.data ? output.data : copy_bytes(NULL, 0);
}

char *webeasy_sanitize(const char *html, int paragraphs_only)
{
    struct dom dom;
    if (!parse_dom(&dom, html)) return NULL;
    Node *clean = element(&dom, "div");
    char *result = NULL;
    if (!clean || !clean_children(&dom, clean, dom.root, paragraphs_only, 0) ||
        !normalize_media(&dom, clean) || !normalize_figures(&dom, clean)) goto done;
    if (paragraphs_only) {
        Node *output = element(&dom, "div"), *paragraph = element(&dom, "p");
        if (!output || !paragraph || !paragraphs_visit(&dom, clean, output, &paragraph) ||
            !flush_paragraph(&dom, output, &paragraph)) goto done;
        result = serialize(output);
    } else if (wrap_paragraphs(&dom, clean)) result = serialize(clean);
done:
    destroy_dom(&dom);
    return result;
}

static int rewrite_home(Node *root, const char *home, int *changed)
{
    for (Node *node = root->first_child; node; node = node->next) {
        if (node->type == LXB_DOM_NODE_TYPE_ELEMENT && node->local_name == LXB_TAG_A) {
            size_t length = 0;
            const unsigned char *href = get_attr(node, "href", &length);
            if (href) {
                size_t base = 0;
                while (base < length && href[base] != '?' && href[base] != '#') base++;
                if ((base == 1 && href[0] == '/') ||
                    (base == 10 && !memcmp(href, "index.html", 10)) ||
                    (base == 12 && !memcmp(href, "./index.html", 12))) {
                    size_t home_length = strlen(home), suffix = length - base;
                    if (home_length + suffix > VALUE_LIMIT) return 0;
                    char *value = malloc(home_length + suffix + 1);
                    if (!value) return 0;
                    memcpy(value, home, home_length);
                    memcpy(value + home_length, href + base, suffix);
                    value[home_length + suffix] = 0;
                    int same = home_length + suffix == length && !memcmp(value, href, length);
                    int ok = same || set_attr(node, "href", value);
                    if (!same) *changed = 1;
                    free(value);
                    if (!ok) return 0;
                }
            }
        }
        if (!rewrite_home(node, home, changed)) return 0;
    }
    return 1;
}

char *webeasy_rich(const char *sanitized_html, const char *home_href)
{
    if (!sanitized_html) return NULL;
    if (home_href && *home_href && strcmp(home_href, "./") &&
        webeasy_safe_href(home_href) != 1) return NULL;
    struct dom dom;
    if (!parse_dom(&dom, sanitized_html)) return NULL;
    char *result = NULL;
    int changed = 0;
    if (!home_href || !*home_href)
        result = copy_bytes((const unsigned char *)sanitized_html, strlen(sanitized_html));
    else if (rewrite_home(dom.root, home_href, &changed))
        result = changed ? serialize(dom.root) :
                           copy_bytes((const unsigned char *)sanitized_html, strlen(sanitized_html));
    destroy_dom(&dom);
    return result;
}

static int array_valid(cJSON *array)
{
    if (!cJSON_IsArray(array)) return 0;
    size_t count = 0;
    const cJSON *item;
    cJSON_ArrayForEach(item, array)
        if (++count > NODE_LIMIT || !cJSON_IsString(item) || !item->valuestring ||
            bounded_length(item->valuestring, VALUE_LIMIT) > VALUE_LIMIT) return 0;
    return 1;
}

/* Per-call open-addressed sets keep collision checking and media collection
 * linear in the number of strings, without mutable global state or modifying
 * the caller's JSON. Keys borrow cJSON's strings and never outlive the call. */
static size_t string_slot(struct strings *strings, const char *value)
{
    uint64_t hash = UINT64_C(14695981039346656037);
    for (const unsigned char *c = (const unsigned char *)value; *c; c++) {
        hash ^= *c;
        hash *= UINT64_C(1099511628211);
    }
    size_t slot = (size_t)hash & (strings->capacity - 1);
    while (strings->slots[slot] && strcmp(strings->slots[slot], value))
        slot = (slot + 1) & (strings->capacity - 1);
    return slot;
}

static int strings_init(struct strings *strings, cJSON *array, size_t expected)
{
    strings->capacity = 16;
    while (strings->capacity < expected * 2 + 1) strings->capacity *= 2;
    strings->slots = calloc(strings->capacity, sizeof *strings->slots);
    if (!strings->slots) return 0;
    const cJSON *item;
    cJSON_ArrayForEach(item, array)
        strings->slots[string_slot(strings, item->valuestring)] = item->valuestring;
    return 1;
}

char *webeasy_heading_id(const char *text, cJSON *used_ids)
{
    size_t length = bounded_length(text, HTML_LIMIT);
    if (!text || length > HTML_LIMIT || !array_valid(used_ids) ||
        cJSON_GetArraySize(used_ids) >= (int)NODE_LIMIT) return NULL;
    utf8proc_uint8_t *normalized = NULL;
    utf8proc_ssize_t size = utf8proc_map((const utf8proc_uint8_t *)text,
                    (utf8proc_ssize_t)length, &normalized, UTF8PROC_COMPAT | UTF8PROC_DECOMPOSE);
    if (size < 0) return NULL;
    if ((size_t)size > OUTPUT_LIMIT) { free(normalized); return NULL; }
    char base[ID_LIMIT + 1] = {0};
    size_t count = 0;
    int pending_separator = 0;
    /* NFKD first, remove exactly U+0300..036F (not every Unicode mark), then
     * lowercase. Case folding is intentionally NOT used: ß must not become ss.
     * Delaying separators strips edge dashes before/after the 25-byte cut. */
    for (utf8proc_ssize_t offset = 0; offset < size && count < ID_LIMIT;) {
        utf8proc_int32_t point;
        utf8proc_ssize_t bytes = utf8proc_iterate(normalized + offset, size - offset, &point);
        if (bytes <= 0) { free(normalized); return NULL; }
        offset += bytes;
        if (point >= 0x0300 && point <= 0x036f) continue;
        point = utf8proc_tolower(point);
        if ((point >= 'a' && point <= 'z') || (point >= '0' && point <= '9')) {
            if (pending_separator && count && count < ID_LIMIT) base[count++] = '-';
            if (count < ID_LIMIT) base[count++] = (char)point;
            pending_separator = 0;
        } else pending_separator = 1;
    }
    free(normalized);
    while (count && base[count - 1] == '-') base[--count] = 0;
    if (!count) { memcpy(base, "label", 6); count = 5; }
    char id[ID_LIMIT + 1];
    memcpy(id, base, count + 1);
    struct strings strings;
    if (!strings_init(&strings, used_ids, (size_t)cJSON_GetArraySize(used_ids))) return NULL;
    for (size_t suffix = 1; strings.slots[string_slot(&strings, id)]; suffix++) {
        char ending[32];
        int ending_length = snprintf(ending, sizeof ending, "-%zu", suffix);
        if (ending_length < 0 || (size_t)ending_length >= ID_LIMIT) {
            free(strings.slots);
            return NULL;
        }
        size_t keep = count < ID_LIMIT - (size_t)ending_length ?
                      count : ID_LIMIT - (size_t)ending_length;
        while (keep && base[keep - 1] == '-') keep--;
        memcpy(id, base, keep);
        memcpy(id + keep, ending, (size_t)ending_length + 1);
    }
    free(strings.slots);
    char *result = copy_bytes((const unsigned char *)id, strlen(id));
    cJSON *item = result ? cJSON_CreateString(id) : NULL;
    if (!item || !cJSON_AddItemToArray(used_ids, item)) {
        free(result);
        cJSON_Delete(item);
        return NULL;
    }
    return result;
}

static int collect_paths(Node *root, struct strings *strings, cJSON *pending, size_t *count)
{
    for (Node *node = root->first_child; node; node = node->next) {
        if (node->type == LXB_DOM_NODE_TYPE_ELEMENT && node->ns == LXB_NS_HTML &&
            media_tag(node->local_name)) {
            for (int poster = 0; poster < 2; poster++) {
                if (poster && node->local_name != LXB_TAG_VIDEO) continue;
                size_t length = 0;
                const unsigned char *value = get_attr(node, poster ? "poster" : "src", &length);
                if (!value) continue;
                if (length > VALUE_LIMIT) return 0;
                char *path = copy_bytes(value, length);
                if (!path) return 0;
                int valid = webeasy_local_media(path), ok = valid >= 0;
                size_t slot = string_slot(strings, path);
                if (valid == 1 && !strings->slots[slot]) {
                    if (*count >= NODE_LIMIT)
                        ok = 0;
                    else {
                        cJSON *item = cJSON_CreateString(path);
                        if (!item || !cJSON_AddItemToArray(pending, item)) {
                            cJSON_Delete(item);
                            ok = 0;
                        } else {
                            strings->slots[slot] = item->valuestring;
                            ++*count;
                        }
                    }
                }
                free(path);
                if (!ok) return 0;
            }
        }
        if (!collect_paths(node, strings, pending, count)) return 0;
    }
    return 1;
}

int webeasy_collect_media(const char *html, cJSON *paths)
{
    if (!array_valid(paths)) return -1;
    struct dom dom;
    if (!parse_dom(&dom, html)) return -1;
    cJSON *pending = cJSON_CreateArray();
    size_t count = (size_t)cJSON_GetArraySize(paths), expected = count + dom.nodes * 2;
    if (expected > NODE_LIMIT) expected = NODE_LIMIT;
    struct strings strings = {0};
    int ok = pending && strings_init(&strings, paths, expected) &&
             collect_paths(dom.root, &strings, pending, &count);
    free(strings.slots);
    destroy_dom(&dom);
    /* Commit only once parsing and every allocation succeeded. Detach/add
     * merely relinks existing nodes, so it cannot allocate during this step. */
    if (ok) {
        while (pending->child) {
            cJSON *item = cJSON_DetachItemViaPointer(pending, pending->child);
            cJSON_AddItemToArray(paths, item);
        }
    }
    cJSON_Delete(pending);
    return ok ? 0 : -1;
}
