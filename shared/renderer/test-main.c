#include "renderer.h"
#include <stdio.h>
#include <stdlib.h>

int main(void)
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
    char *output = webeasy_render(input);
    free(input);
    if (!output) return 1;
    puts(output);
    webeasy_free(output);
    return 0;
}
