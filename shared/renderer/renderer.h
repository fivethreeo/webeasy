#ifndef WEBEASY_RENDERER_H
#define WEBEASY_RENDERER_H

#ifdef __cplusplus
extern "C" {
#endif

/* JSON request: {partials, documents:[{path,template,values}], rawSlots}.
 * Returns owned JSON: {files:[{path,html}]} or {error}. */
char *webeasy_render(const char *request);
void webeasy_free(void *memory);

#ifdef __cplusplus
}
#endif
#endif
