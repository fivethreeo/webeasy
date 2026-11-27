#ifndef WEBEASY_RENDERER_H
#define WEBEASY_RENDERER_H

#include "site.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Low-level Mustache rendering, also used for individual template previews.
 * JSON request: {partials, documents:[{path,template,values}], rawSlots}.
 * Returns owned JSON: {files:[{path,html}]} or {error}.
 * Site generation is the higher-level API declared in site.h. */
char *webeasy_render(const char *request);
void webeasy_free(void *memory);

#ifdef __cplusplus
}
#endif
#endif
