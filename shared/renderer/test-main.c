#include "renderer.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    size_t size = 0, capacity = 4096;
    char *input = malloc(capacity);
    if (!input) return 1;
    int byte;
    while ((byte = getchar()) != EOF) {
        if (size + 1 == capacity) {
            capacity *= 2;
            char *expanded = realloc(input, capacity);
            if (!expanded) { free(input); return 1; }
            input = expanded;
        }
        input[size++] = (char)byte;
    }
    input[size] = 0;
    char *output = argc < 2 ? webeasy_render(input) :
        !strcmp(argv[1], "normalize") ? webeasy_normalize(input) :
        !strcmp(argv[1], "prepare") ? webeasy_prepare(input) :
        !strcmp(argv[1], "compile") ? webeasy_compile(input) : NULL;
    free(input);
    if (!output) return 1;
    puts(output);
    webeasy_free(output);
    return 0;
}
