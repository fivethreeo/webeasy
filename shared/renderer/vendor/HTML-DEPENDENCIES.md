# C HTML dependencies

These are pinned public upstream source snapshots. No application data is
included. The retained upstream source files are unmodified; unused modules,
upstream tests, examples, packaging and data-generation tools are omitted.
Use `../html-dependencies.json`, not upstream project-wide source globs.

| Dependency | Pin / source | License |
| --- | --- | --- |
| Lexbor | [v2.5.0](https://github.com/lexbor/lexbor/tree/v2.5.0) | Apache-2.0; see `lexbor/LICENSE` and `lexbor/NOTICE` |
| utf8proc | [v2.10.0](https://github.com/JuliaStrings/utf8proc/tree/v2.10.0) | MIT and Unicode data notices; see `utf8proc/LICENSE.md` |

Immutable upstream commits are recorded in `../html-dependencies.json`:
Lexbor `7fb22cf5664a331d7c24b113489e566767c9c25a`, utf8proc
`a1b99daa2a3393884220264c927a48ba1251a9c6`.

Lexbor supplies HTML5 tokenization, fragment tree construction, entity decoding,
DOM operations, serialization and WHATWG URL parsing. Its URL parser also
requires the encoding, Unicode/IDNA and punycode modules. Only the platform
`core/memory.c` implementation is needed; the application does not install
process-global allocator hooks. Neither filesystem/performance code nor threads
are required.

utf8proc supplies validated UTF-8 iteration, NFKD normalization and Unicode
lowercasing. Compile `utf8proc.c` only: it includes `utf8proc_data.c`.
All application state is per-call, so independent renderer calls can run
concurrently.

## Ownership and failure behavior

`html.h` describes the public contract. Every successful string return is
malloc-owned. DOM/parser arenas and temporary buffers are freed before return.
JSON arrays own added strings; failures do not partially append to them.
No regex or homemade HTML parser is used, and parse/allocation/limit failures
never fall back to unsanitized input.

HTML input is limited to 2 MiB, serialization to 16 MiB, traversal/parser stacks
to depth 128, and parsed tokens/cumulative DOM allocations to 100,000. Input
must be UTF-8. URL/attribute values are limited to 64 KiB, and JSON string arrays
to 100,000 entries. Parsing tolerates malformed HTML using HTML5 recovery; a
token callback only enforces work limits and delegates every token to Lexbor.

## Compatibility

The allowed HTML, attribute checks, h1/b conversion, figure/caption handling,
inline paragraph wrapping, paragraph-only output and Prosango ID algorithm
follow `website/src/rich-text.js` and `website/src/heading-id.js`.
Focused C checks and a JavaScript differential suite live in `../test-html.c`
and `../test-html.mjs`.
Compile the standalone C test with `-DWEBEASY_HTML_THREADS -pthread` to also
exercise eight independent concurrent callers (POSIX test builds only).

Deliberate differences:

- Lexbor additionally escapes `<` and `>` in quoted attribute values.
  These remain the identical DOM value, with stricter serialization.
- Invalid UTF-8 and excessive input/work return failure instead of attempting
  JavaScript's replacement-character processing or unlimited work.
- String arrays are explicitly validated rather than accepting non-string
  entries.

`webeasy_rich` requires already-sanitized HTML. If no anchor needs rewriting,
it returns the original bytes; otherwise it serializes the parsed fragment.
`./` is allowed as a generated home destination, even though a stored link
with a dot path segment is rejected by the sanitizer. Only actual anchor
`href` values are changed, never captions, titles or literal text.
