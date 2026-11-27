#ifndef WEBEASY_SITE_H
#define WEBEASY_SITE_H

/* The native apps send project JSON directly to these functions. No JavaScript
 * runtime, browser DOM, filesystem or network connection is used by generation.
 * Each result is owned UTF-8 JSON, released with webeasy_free():
 * normalize -> {project}; prepare -> {project,renderRequest,files,referencedMedia};
 * compile -> {project,files,referencedMedia}. Failures return {error}. */
char *webeasy_normalize(const char *project);
char *webeasy_prepare(const char *input);
char *webeasy_compile(const char *input);

#endif
