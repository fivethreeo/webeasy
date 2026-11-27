#include "html.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef WEBEASY_HTML_THREADS
#include <pthread.h>
#endif

static void equal_sanitize(const char *input, int short_text, const char *expected)
{
    char *actual = webeasy_sanitize(input, short_text);
    if (!actual || strcmp(actual, expected)) {
        fprintf(stderr, "Sanitize mismatch:\nExpected: %s\nActual: %s\n",
                expected, actual ? actual : "(failure)");
        abort();
    }
    char *again = webeasy_sanitize(actual, short_text);
    assert(again && !strcmp(again, actual));
    free(actual);
    free(again);
}

static void equal_id(cJSON *used, const char *input, const char *expected)
{
    char *actual = webeasy_heading_id(input, used);
    assert(actual && !strcmp(actual, expected));
    free(actual);
}

static void checks(void)
{
    equal_sanitize("It&nbsp;<strong>works</strong>!<p></p>", 0,
                   "<p>It&nbsp;<strong>works</strong>!</p><p></p>");
    equal_sanitize("<h1>Hello</h1><b>Bold <a href='about/'>link</a></b>", 0,
                   "<h2>Hello</h2><p><strong>Bold <a href=\"about/\">link</a></strong></p>");
    equal_sanitize("<p onclick='bad()'>A<script>bad()</script><iframe>bad</iframe>"
                   "<svg><a href='https://evil.invalid'>bad</a></svg>"
                   "<a href='java&#10;script:bad()' target='_blank'>link</a></p>", 0,
                   "<p>A<a>link</a></p>");
    equal_sanitize("<figure><img src='media/photo.jpg' onerror='bad()'>Our <strong>coast</strong>"
                   "<p>A note.</p><figcaption>Existing caption.</figcaption></figure>", 0,
                   "<figure class=\"stack\"><img src=\"media/photo.jpg\" alt=\"\" loading=\"lazy\">"
                   "<figcaption>Our <strong>coast</strong><p>A note.</p>Existing caption.</figcaption></figure>");
    equal_sanitize("<video autoplay poster='assets/coast.svg' src='https://example.invalid/clip.mp4'>"
                   "<source src='media/clip.mp4' type='video/mp4'></video><source src='media/orphan.mp4'>", 0,
                   "<figure class=\"stack\"><video poster=\"assets/coast.svg\" controls=\"\" playsinline=\"\" "
                   "preload=\"metadata\"><source src=\"media/clip.mp4\" type=\"video/mp4\"></video></figure>");
    equal_sanitize("<h2>Intro <strong>bold</strong></h2><ul><li>One</li><li><em>Two</em></li></ul>"
                   "<blockquote>Quote</blockquote><figure><img src='media/photo.jpg'><figcaption>Caption</figcaption>"
                   "</figure><div>New paragraph <a href='/about/'>link</a></div>", 1,
                   "<p>Intro <strong>bold</strong></p><p>One</p><p><em>Two</em></p><p>Quote</p>"
                   "<p>Caption</p><p>New paragraph <a href=\"/about/\">link</a></p>");
    equal_sanitize(" \t<figure>&nbsp;</figure> <img src='media/../secret.jpg'>", 1, "");
    equal_sanitize("<p><img src='https://remote.invalid/a' width='01'>Text<br></p>", 1,
                   "<p>Text<br></p>");
    const char *safe[] = {"https://example.com/path?q=1", "http://example.com/", "mailto:hello@example.com",
        "tel:+4712345678", "/", "index.html", "about/team/", "#section",
        "https://[::1]:443/", "https://éxample.org", "?query=%", "path%2Fchild", NULL};
    const char *unsafe[] = {"javascript:alert(1)", "java\nscript:alert(1)", "data:text/html,hello",
        "file:///private/secret", "content://photos/1", "//example.com/", "\\\\example.com",
        "../private", "about/%2e%2e/private", "mailto:", "https://example.com:99999/",
        "https://[invalid]", "https://", "path%ZZ", "%ff", "about%2f..%2fprivate", NULL};
    for (int i = 0; safe[i]; i++) assert(webeasy_safe_href(safe[i]) == 1);
    for (int i = 0; unsafe[i]; i++) assert(webeasy_safe_href(unsafe[i]) == 0);
    assert(webeasy_local_media("media/a-b_2.3/file.jpg") == 1);
    assert(webeasy_local_media("assets/coast.svg") == 1);
    assert(webeasy_local_media("media/%2e%2e/file") == 0);
    assert(webeasy_local_media("media/../file") == 0);
    assert(webeasy_local_media("/media/file") == 0);

    cJSON *used = cJSON_CreateArray();
    equal_id(used, "Héllo & World!", "hello-world");
    equal_id(used, "Hello world", "hello-world-1");
    equal_id(used, "Hello world", "hello-world-2");
    equal_id(used, "Hello world-1", "hello-world-1-1");
    equal_id(used, "!!!", "label");
    equal_id(used, "!!!", "label-1");
    equal_id(used, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaa");
    equal_id(used, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaa-1");
    equal_id(used, "ﬁ Ｆｕｌｌ ① ß", "fi-full-1");
    equal_id(used, "a͏b", "ab");
    int used_count = cJSON_GetArraySize(used);
    assert(!webeasy_heading_id("\xff", used));
    assert(cJSON_GetArraySize(used) == used_count);
    cJSON_Delete(used);

    const char *untouched = "<p><a href='about/'>It&nbsp;works</a></p>";
    char *rich = webeasy_rich(untouched, "./");
    assert(rich && !strcmp(rich, untouched));
    free(rich);
    rich = webeasy_rich("<p><a href='/' title=\"literal\n href='/'\">Home</a>"
                       "<a href='index.html#details'>Details</a>"
                       "<a href='/?q=1&amp;two=2'>Search</a>Literal href='/'</p>", "/host/site/");
    assert(rich && strstr(rich, "href=\"/host/site/\"") &&
           strstr(rich, "href=\"/host/site/#details\"") &&
           strstr(rich, "href=\"/host/site/?q=1&amp;two=2\"") &&
           strstr(rich, "title=\"literal\n href='/'\"") && strstr(rich, "Literal href='/'"));
    free(rich);
    assert(!webeasy_rich("<a href='/'>Home</a>", "javascript:bad()"));

    cJSON *paths = cJSON_CreateArray();
    cJSON_AddItemToArray(paths, cJSON_CreateString("media/clip.mp4"));
    assert(webeasy_collect_media("<p>Type src=\"media/private.jpg\" here.</p>"
        "<video src='media/clip.mp4' poster='media/poster.jpg'>"
        "<source src='media/other.webm'></video><img src='assets/coast.svg'>"
        "<img src='https://remote.invalid/a'><img src='media/../a'>", paths) == 0);
    assert(cJSON_GetArraySize(paths) == 4);
    assert(!strcmp(cJSON_GetArrayItem(paths, 1)->valuestring, "media/poster.jpg"));
    assert(!strcmp(cJSON_GetArrayItem(paths, 2)->valuestring, "media/other.webm"));
    assert(!strcmp(cJSON_GetArrayItem(paths, 3)->valuestring, "assets/coast.svg"));
    assert(webeasy_collect_media("\xff", paths) == -1 && cJSON_GetArraySize(paths) == 4);
    cJSON_Delete(paths);

    char nested[2048] = {0};
    for (int i = 0; i < 150; i++) strcat(nested, "<div>");
    strcat(nested, "Text");
    for (int i = 0; i < 150; i++) strcat(nested, "</div>");
    assert(!webeasy_sanitize(nested, 0));
    char *large = malloc(2u * 1024u * 1024u + 2);
    assert(large);
    memset(large, 'x', 2u * 1024u * 1024u + 1);
    large[2u * 1024u * 1024u + 1] = 0;
    assert(!webeasy_sanitize(large, 0));
    free(large);
    char long_value[65538];
    memset(long_value, 'x', sizeof long_value - 1);
    long_value[sizeof long_value - 1] = 0;
    assert(webeasy_safe_href(long_value) == -1);
    assert(webeasy_local_media(long_value) == -1);
    assert(!webeasy_sanitize("\xff", 0));
    assert(!webeasy_sanitize(NULL, 0));
    equal_sanitize("", 0, "");
    puts("HTML, URL, media, heading and resource checks passed.");
}

#ifdef WEBEASY_HTML_THREADS
static void *thread_checks(void *unused)
{
    (void)unused;
    for (int iteration = 0; iteration < 32; iteration++) {
        char *html = webeasy_sanitize("<h1>Å</h1><a href='https://éxample.org/'>"
                         "Link</a><img src='media/a.jpg'>", 0);
        assert(html && strstr(html, "<h2>Å</h2>") && strstr(html, "<figure class=\"stack\">"));
        cJSON *used = cJSON_CreateArray(), *paths = cJSON_CreateArray();
        assert(used && paths && webeasy_collect_media(html, paths) == 0);
        equal_id(used, "Héllo!", "hello");
        equal_id(used, "Hello", "hello-1");
        assert(cJSON_GetArraySize(paths) == 1);
        free(html);
        cJSON_Delete(used);
        cJSON_Delete(paths);
    }
    return NULL;
}

static void concurrent_checks(void)
{
    pthread_t threads[8];
    for (int i = 0; i < 8; i++) assert(!pthread_create(&threads[i], NULL, thread_checks, NULL));
    for (int i = 0; i < 8; i++) assert(!pthread_join(threads[i], NULL));
    puts("Eight concurrent callers passed.");
}
#endif

/* Optional command mode lets a JavaScript parity test feed exact fixtures into
 * the C implementation without a browser/native bridge or shared-library FFI. */
int main(int argc, char **argv)
{
    if (argc == 1) {
        checks();
#ifdef WEBEASY_HTML_THREADS
        concurrent_checks();
#endif
        return 0;
    }
    char *result = NULL;
    if (argc == 3 && !strcmp(argv[1], "sanitize")) result = webeasy_sanitize(argv[2], 0);
    else if (argc == 3 && !strcmp(argv[1], "short")) result = webeasy_sanitize(argv[2], 1);
    else if (argc == 3 && !strcmp(argv[1], "href")) {
        printf("%d", webeasy_safe_href(argv[2]));
        return 0;
    } else if (argc == 3 && !strcmp(argv[1], "media")) {
        printf("%d", webeasy_local_media(argv[2]));
        return 0;
    } else if (argc == 4 && !strcmp(argv[1], "rich")) result = webeasy_rich(argv[2], argv[3]);
    else if (argc >= 3 && !strcmp(argv[1], "ids")) {
        cJSON *used = cJSON_CreateArray();
        for (int i = 2; i < argc; i++) {
            result = webeasy_heading_id(argv[i], used);
            if (!result) { cJSON_Delete(used); return 1; }
            puts(result);
            free(result);
        }
        cJSON_Delete(used);
        return 0;
    } else return 2;
    if (!result) return 1;
    fputs(result, stdout);
    free(result);
    return 0;
}
