#include "renderer.h"
#include "vendor/cjson/cJSON.h"
#include "vendor/mustach/mustach.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_OUTPUT (16u * 1024u * 1024u)
#define MAX_REQUEST (64u * 1024u * 1024u)
#define MAX_COMPOSITION 64

struct renderer {
    const cJSON *partials;
    const cJSON *raw_slots;
    char error[256];
};

struct context {
    struct renderer *renderer;
    const cJSON *values[MUSTACH_MAX_DEPTH + 1];
    int array[MUSTACH_MAX_DEPTH + 1];
    int depth;
    int partial_depth;
    char *output;
    size_t size;
    size_t capacity;
};

static int fail(struct renderer *renderer, const char *message, const char *name)
{
    snprintf(renderer->error, sizeof renderer->error, "%s%s", message, name ? name : "");
    return MUSTACH_ERROR_USER(1);
}

static int append(struct context *context, const char *text, size_t size)
{
    if (size > MAX_OUTPUT - context->size)
        return fail(context->renderer, "Rendered document is too large.", NULL);
    size_t needed = context->size + size + 1;
    if (needed > context->capacity) {
        size_t capacity = context->capacity ? context->capacity : 1024;
        while (capacity < needed) capacity *= 2;
        char *output = realloc(context->output, capacity);
        if (!output) return fail(context->renderer, "Out of memory.", NULL);
        context->output = output;
        context->capacity = capacity;
    }
    memcpy(context->output + context->size, text, size);
    context->size += size;
    context->output[context->size] = 0;
    return MUSTACH_OK;
}

static int emit(void *closure, const char *text, size_t size, int escape, FILE *file)
{
    (void)file;
    struct context *context = closure;
    if (!escape) return append(context, text, size);
    for (size_t index = 0, start = 0; index <= size; index++) {
        const char *entity = NULL;
        if (index < size) {
            switch (text[index]) {
                case '&': entity = "&amp;"; break;
                case '<': entity = "&lt;"; break;
                case '>': entity = "&gt;"; break;
                case '"': entity = "&quot;"; break;
                case '\'': entity = "&#39;"; break;
            }
        }
        if (entity || index == size) {
            int status = append(context, text + start, index - start);
            if (!status && entity) status = append(context, entity, strlen(entity));
            if (status) return status;
            start = index + 1;
        }
    }
    return MUSTACH_OK;
}

static const cJSON *lookup(struct context *context, const char *name)
{
    if (!strcmp(name, ".")) return context->values[context->depth];
    char key[MUSTACH_MAX_LENGTH + 1];
    const char *dot = strchr(name, '.');
    size_t length = dot ? (size_t)(dot - name) : strlen(name);
    if (length > MUSTACH_MAX_LENGTH) return NULL;
    memcpy(key, name, length);
    key[length] = 0;
    const cJSON *value = NULL;
    for (int depth = context->depth; depth >= 0 && !value; depth--)
        value = cJSON_GetObjectItemCaseSensitive(context->values[depth], key);
    while (value && dot) {
        name = dot + 1;
        dot = strchr(name, '.');
        length = dot ? (size_t)(dot - name) : strlen(name);
        memcpy(key, name, length);
        key[length] = 0;
        value = cJSON_GetObjectItemCaseSensitive(value, key);
    }
    return value;
}

static int put(void *closure, const char *name, int escape, FILE *file)
{
    struct context *context = closure;
    if (!escape) {
        const cJSON *slot;
        int allowed = 0;
        cJSON_ArrayForEach(slot, context->renderer->raw_slots)
            if (cJSON_IsString(slot) && !strcmp(slot->valuestring, name)) allowed = 1;
        if (!allowed) return fail(context->renderer, "Unsupported raw slot: ", name);
    }
    const cJSON *value = lookup(context, name);
    if (!value) return fail(context->renderer, "Missing string field: ", name);
    if (cJSON_IsNull(value)) return MUSTACH_OK;
    if (cJSON_IsString(value))
        return emit(closure, value->valuestring, strlen(value->valuestring), escape, file);
    if (cJSON_IsBool(value))
        return emit(closure, cJSON_IsTrue(value) ? "true" : "false", cJSON_IsTrue(value) ? 4 : 5, escape, file);
    if (cJSON_IsNumber(value)) {
        char number[64];
        snprintf(number, sizeof number, "%.17g", value->valuedouble);
        return emit(closure, number, strlen(number), escape, file);
    }
    return fail(context->renderer, "Expected a scalar field: ", name);
}

static int enter(void *closure, const char *name)
{
    struct context *context = closure;
    const cJSON *value = lookup(context, name);
    if (!value || cJSON_IsNull(value) || cJSON_IsFalse(value) ||
        (cJSON_IsString(value) && !*value->valuestring) ||
        (cJSON_IsArray(value) && !value->child)) return 0;
    if (context->depth == MUSTACH_MAX_DEPTH)
        return fail(context->renderer, "Template context is too deep.", NULL);
    context->depth++;
    context->array[context->depth] = cJSON_IsArray(value);
    context->values[context->depth] = cJSON_IsArray(value) ? value->child : value;
    return 1;
}

static int next(void *closure)
{
    struct context *context = closure;
    if (!context->array[context->depth] || !context->values[context->depth]->next) return 0;
    context->values[context->depth] = context->values[context->depth]->next;
    return 1;
}

static int leave(void *closure)
{
    struct context *context = closure;
    context->depth--;
    return MUSTACH_OK;
}

static void release_partial(const char *value, void *closure)
{
    (void)value;
    struct context *context = closure;
    context->partial_depth--;
}

static int partial(void *closure, const char *name, struct mustach_sbuf *buffer)
{
    struct context *context = closure;
    if (context->partial_depth >= 32)
        return fail(context->renderer, "Invalid template: partial nesting is too deep.", NULL);
    const cJSON *source = cJSON_GetObjectItemCaseSensitive(context->renderer->partials, name);
    if (!cJSON_IsString(source))
        return fail(context->renderer, "Template is missing: ", name);
    buffer->value = source->valuestring;
    context->partial_depth++;
    buffer->releasecb = release_partial;
    buffer->closure = context;
    return MUSTACH_OK;
}

static char *render(struct renderer *renderer, const char *name, const cJSON *values)
{
    const cJSON *source = cJSON_GetObjectItemCaseSensitive(renderer->partials, name);
    if (!cJSON_IsString(source)) {
        fail(renderer, "Template is missing: ", name);
        return NULL;
    }
    struct context context = { .renderer = renderer, .values = { values } };
    const struct mustach_itf interface = {
        .put = put, .enter = enter, .next = next, .leave = leave, .partial = partial, .emit = emit,
    };
    int status = mustach_file(source->valuestring, 0, &interface, &context, Mustach_With_NoExtensions, NULL);
    if (status) {
        if (!*renderer->error)
            snprintf(renderer->error, sizeof renderer->error, "Invalid template %s (Mustach error %d).", name, status);
        free(context.output);
        return NULL;
    }
    if (!context.output) {
        context.output = malloc(1);
        if (context.output) *context.output = 0;
    }
    return context.output;
}

/* Deferred renders preserve legacy content slots while also exposing structured data. */
static int expand(struct renderer *renderer, cJSON *parent, int depth)
{
    if (depth > MAX_COMPOSITION) return fail(renderer, "Template composition is too deep.", NULL);
    cJSON *value = parent->child;
    while (value) {
        cJSON *following = value->next;
        if (cJSON_IsObject(value) || cJSON_IsArray(value)) {
            if (expand(renderer, value, depth + 1)) return -1;
            const cJSON *name = cJSON_GetObjectItemCaseSensitive(value, "$render");
            if (name) {
                const cJSON *data = cJSON_GetObjectItemCaseSensitive(value, "values");
                if (!cJSON_IsString(name) || !cJSON_IsObject(data))
                    return fail(renderer, "Invalid template composition.", NULL);
                char *html = render(renderer, name->valuestring, data);
                if (!html) return -1;
                cJSON *replacement = cJSON_CreateString(html);
                free(html);
                int replaced = replacement && (cJSON_IsObject(parent)
                    ? cJSON_ReplaceItemInObjectCaseSensitive(parent, value->string, replacement)
                    : cJSON_ReplaceItemViaPointer(parent, value, replacement));
                if (!replaced) {
                    cJSON_Delete(replacement);
                    return fail(renderer, "Out of memory.", NULL);
                }
            }
        }
        value = following;
    }
    return 0;
}

char *webeasy_render(const char *request)
{
    struct renderer renderer = {0};
    cJSON *input = NULL, *output = cJSON_CreateObject(), *files = cJSON_CreateArray();
    if (!output || !files) goto cleanup;
    if (!request || strlen(request) > MAX_REQUEST) {
        fail(&renderer, "Render request is missing or too large.", NULL);
        goto error;
    }
    input = cJSON_ParseWithOpts(request, NULL, 1);
    renderer.partials = cJSON_GetObjectItemCaseSensitive(input, "partials");
    renderer.raw_slots = cJSON_GetObjectItemCaseSensitive(input, "rawSlots");
    cJSON *documents = cJSON_GetObjectItemCaseSensitive(input, "documents");
    if (!cJSON_IsObject(renderer.partials) || !cJSON_IsArray(documents) ||
        !cJSON_IsArray(renderer.raw_slots)) {
        fail(&renderer, "Invalid render request.", NULL);
        goto error;
    }
    cJSON *document;
    cJSON_ArrayForEach(document, documents) {
        const cJSON *path = cJSON_GetObjectItemCaseSensitive(document, "path");
        const cJSON *name = cJSON_GetObjectItemCaseSensitive(document, "template");
        cJSON *values = cJSON_GetObjectItemCaseSensitive(document, "values");
        if (!cJSON_IsString(path) || !cJSON_IsString(name) || !cJSON_IsObject(values)) {
            fail(&renderer, "Invalid render document.", NULL);
            goto error;
        }
        if (expand(&renderer, values, 0)) goto error;
        char *html = render(&renderer, name->valuestring, values);
        if (!html) goto error;
        cJSON *file = cJSON_CreateObject();
        if (!file || !cJSON_AddStringToObject(file, "path", path->valuestring) ||
            !cJSON_AddStringToObject(file, "html", html) || !cJSON_AddItemToArray(files, file)) {
            free(html);
            cJSON_Delete(file);
            fail(&renderer, "Out of memory.", NULL);
            goto error;
        }
        free(html);
    }
    cJSON_AddItemToObject(output, "files", files);
    files = NULL;
    goto cleanup;
error:
    cJSON_AddStringToObject(output, "error", *renderer.error ? renderer.error : "Cannot render website.");
cleanup:
    ;
    char *result = output ? cJSON_PrintUnformatted(output) : NULL;
    cJSON_Delete(files);
    cJSON_Delete(output);
    cJSON_Delete(input);
    return result;
}

void webeasy_free(void *memory)
{
    free(memory);
}
