# Webeasy

One native iOS app and one native Android app for a phone-based webpage editor.
Both apps use native SwiftUI/Jetpack Compose navigation, lists, metadata fields,
date pickers, and section/aside/subpage controls. Only rich-text content fields
and website previews use WebView. Content editing opens a full-screen WebView
with persistent image/video actions and a horizontally scrollable formatting bar.
Select text to toggle inline marks, or set a mark at the caret before typing.
Pages and subpages have a native **Page layout** picker: Simple page, Landing page,
or Reading page. Layouts are defined in the Rsbuild catalog and bundled into the
apps after building; existing SQLite projects migrate without losing content.
They have native local SQLite storage,
phone photo/video pickers, and static-site export. Template
examples and the building-block showcase are also bundled. Hosting setup QR codes
can be read from saved pictures/screenshots or a camera photo. The Hosting tab
publishes the generated website directly using FTP, explicit FTPS, or SFTP.
Dropbox backup is not implemented.

**Settings** contains the Simple/Advanced mode selector and **Templates**.
Links use a native full-screen form like image details, with page selection,
URL and text fields, and Remove link inside the edit view.
Android uses a native bottom navigation bar on phones and a left navigation rail
on tablets (smallest window width at least 600dp), with matching outline symbols
for Pages, News, Settings, Changes, and Hosting. Navigation stays out of pushed
editing screens; normal Back navigation and unsaved-change prompts are preserved.
The apps default to **Simple** mode and remember the choice on the device.
Simple hides sections, covers, template loading, and the public URL prefix;
asides and subpages remain available. Switching modes never deletes hidden
content or resets saved publishing settings. **Advanced** exposes every option.
Simple image dialogs default to a 400-pixel maximum width and JPEG quality 85%;
these are editable defaults, not forced values. Advanced starts with both unset.

News has separate **Short content** and **Long content** fields. Short content
supports only paragraphs, inline formatting, and links, with no headings, lists,
quotes, images, or videos. It supplies the latest-news summaries; long content
supplies the full story and supports normal rich text. Existing short-field
markup is copied into the new long field when migrating, preserving old media.
The rich-text toolbar bundles Prosango's actual SVG icons for bold, italic,
emphasis, strike, underline, lists, links, images, and video, with accessible labels; unmatched controls retain
text labels.

Advanced mode offers optional covers for pages, subpages, and news. Covers
appear below the title and on news summaries, require an original and final
width of at least 1200 pixels, and support optional resizing/JPEG quality.
Removing a cover clears its reference without deleting the imported media.

## Structure

```text
apps/ios/       SwiftUI app, generated with XcodeGen (iOS 17+)
apps/android/   Kotlin / Jetpack Compose app (Android 8+)
website/        Independently built website HTML, CSS, JavaScript, and media
scripts/        Packaging and integration tests
shared/         SQLite schema and shared C/Mustach renderer
```

The native tooling follows `../blakken-project`, without its backend dependencies
or restaurant-specific apps. The website uses Rsbuild and reuses
the reference project's Every Layout CSS primitives. There is no backend.

## Website build and preview

Requires Node.js 22+, npm, Emscripten 4.0.20 (`emcc`), a host C compiler (`cc`),
and Python 3 for the publisher tests. Set `EMCC` to the compiler path if it is not
on `PATH`. CI installs the pinned WebAssembly toolchain.

```sh
npm ci
npm test
npm run package:website
npm run dev --workspace website
```

Open `http://127.0.0.1:4173` for the template gallery and component showcase.
Open `/editor.html` for the browser editor preview, `/home.html` for the generated
example homepage, `/about/team/` for a subpage, and `/news/` for the latest news.
The standalone `/field.html` page is the full-screen rich-text editor opened from
native forms; it does not edit metadata or navigation. Browser edits are deliberately in-memory only; persistent storage is native SQLite
in the installed phone apps, not browser `localStorage`.
The Rsbuild development server also serves `/provision.html`, the hosting QR setup
tool. CSS/JS changes hot-reload; catalog/template/component/media changes reload
their generated examples.
`npm run build` builds only `website/dist`. `npm run package:website` also copies
the complete output into both apps. Generated output is ignored by Git.
Development example pages live separately in `website/.preview`, so building an
app does not remove the running development server's QR page.

## Define templates and components

Register each template in `website/src/catalog.json` with a unique ID, title,
description, `templates/<id>.html` source path, and `example` data. Its `blocks`
array names the components included in that example. Register each insertable
component in the same catalog with a `components/<id>.html` file and example data.
Every registered item automatically gets an example page and a native app entry.
Builds fail for missing fields, files, example media, or unknown components.

Components are ready-made, specific content blocks (text sections, captioned
images, video players, callouts), not a generic layout builder. Every Layout
primitives are internal CSS implementation details: no stack/grid/cluster
components, CSS controls, or HTML source panels are exposed in the app showcase.

Open **Settings > Templates** for **Load templates from server** in Advanced mode. Publishing creates
`webeasy_templates/templates/*.html` (including `master.html`) and
`webeasy_templates/components/*.html` under the configured upload destination.
Only missing source files are uploaded; existing server edits are preserved.
Edit these UTF-8 files on your own hosting, then load them into the app. All files
must load and validate before any local source is replaced. Loaded sources are
saved in SQLite and survive restarts and app updates; previews, exports, and the
next publish use them. This loads existing registered templates, not new catalog
entries. Each file is limited to 256 KiB. Loading templates does not save or
replace your page/news content.

The Templates screen lists **Page templates** and **Reusable partials** in both
editor modes. Every catalog entry can be opened to read/select its current source
and preview its example, including loaded server overrides. Loading server
templates remains an Advanced-mode action.

The engine is Mustach 1.2.10 (0BSD) with cJSON 1.7.19 (MIT), vendored under
`shared/renderer/vendor/` with their original notices. Builds also package
`renderer-licenses.txt` into both apps. The same C implementation runs through
JNI on Android, directly from Swift on iOS, and as WebAssembly in the browser and
Node build. There is no filesystem lookup or executable template code.

Use `{{field}}` for escaped text/attributes, `{{#items}}...{{/items}}` for loops
or conditions, `{{^items}}...{{/items}}` for empty/false cases, and `{{>name}}` for
catalog partials. Dotted names access nested data. Raw interpolation is restricted
to `{{{html}}}` (sanitized rich text), `{{{content}}}` (composed content), and
`{{{navigation}}}` (composed navigation). Plain text such as titles cannot bypass
escaping. Unknown fields/partials, malformed sections, oversized output and
excessive nesting fail the whole render instead of producing a partial website.

Page/news layouts receive structured `sections`, `asides`, `cover`, `children`
(page links), and `html`, alongside their title/date metadata. Sections expose
`heading`, `headingId`, `subheading`, `html`, and `asides`. Covers expose `src`
and `alt`. News listings expose `items` (latest three) and `allItems` (all sorted
summaries), each with `href`, title, date, rich `html`, and an optional cover.
The master also receives `page` or `news` data, `menu`, `homeHref`, current/active
states, `title`, and `baseHref`.

The `site-header`, `navigation-item`, `breadcrumbs`, `subpage-navigation`,
`subpage-item`, `page-content`, `body`, `main-content`, `with-asides`, `section`,
`section-content`, `aside`, `aside-content`, `cover-image`, `rich-content`, `news-list`, `news-summary`, `news-empty`,
and `site-footer` partials own the published markup. Existing `{{{content}}}`
and `{{{navigation}}}` layouts continue working; new layouts can instead loop
over structured data and compose partials directly. A content slot is no longer
mandatory. The footer is empty until customized.

Templates are trusted code from your bundle or your own hosting. Keep the master
template's base URL, local stylesheet/script references and Content Security
Policy unless you deliberately want to change those behaviors.
Add your styles and scripts in `website/src/site.css` and `site.js`, and bundled
media in `website/src/assets/`. Example media paths are relative to the site root.
The video example deliberately has a poster but no clip; it is a placeholder
until a project supplies video media.

Owned sections have an optional plain-text subheading. Their header contains
the heading and optional paragraph together; `aria-labelledby` links the section
to the heading's generated ID. IDs follow Prosango's normalized 25-character
slugs, with per-document collisions numbered `-1`, `-2`, and so on.

The generated `catalog.json` includes HTML source, example data, and preview paths
for both native clients and future editing/generation code. All previews use the
same bundled CSS/JS and media, with no CDN dependencies.

Native publishing still uses the shared JavaScript sanitizer/normalizer to
prepare validated template data; HTML generation itself is C. The helper
WebView therefore remains for normalization and other existing utilities.
`npm run build:renderer` rebuilds the ignored WebAssembly module after C changes.
The editor/helper CSP allows WebAssembly compilation without allowing JavaScript
`eval`; published pages retain their existing stricter CSP.

## iOS

On macOS, install Xcode and XcodeGen, then:

```sh
npm ci
npm run package:website
cd apps/ios
xcodegen generate
open Webeasy.xcodeproj
```

Choose your development team for device signing. A pre-build phase rebuilds and
packages the website, so CSS/JS and catalog edits are included in the next app build.
Packaging before XcodeGen is required so the resource directory exists.
Xcode must be able to find `node`, `npm`, and Emscripten 4.0.20 (`emcc`) in its
build environment for the bundled browser assets. The app itself compiles the
shared C renderer directly through its Swift bridging header.
Website resources are a folder reference, preserving relative media and page paths.

## Android

Requires JDK 17+, Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1.
The wrapper downloads Gradle 8.9; Gradle can install the pinned native tools when
their Android SDK licenses are accepted. Bundling browser assets also requires
Emscripten 4.0.20 (`emcc`).

```sh
npm ci
cd apps/android
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Set `ANDROID_HOME` or provide `local.properties` with your SDK location.
The Android `preBuild` task rebuilds and packages the website automatically.
Generated pages are rendered by the shared C library through JNI on a worker;
the helper WebView still normalizes and sanitizes content before rendering.
The APK is generated in `app/build/outputs/apk/debug/`.
Previews use AndroidX WebKit's local HTTPS asset loader, not unrestricted file URLs.

Android's hosting QR camera uses a full-resolution photo instead of a thumbnail.
The temporary capture file is deleted after loading the image or cancelling the camera.

## Current boundaries

The editor supports pages/subpages, dated news, ordered owned sections/asides,
rich text, phone media import, website export and publishing. Going back from a
changed page or news form asks to Save, Don't save, or Cancel. Discard restores
that item's owned content without discarding unrelated drafts. Save explicitly
before closing; export and publishing save their content snapshot first. Android exports a ZIP through the share
sheet, and iOS exports a website folder through Files. Only referenced media,
generated HTML, template source files, and website assets are exported, never the editable SQLite
database, editor, catalog, or hosting credentials. Backup remains a future
integration. Keep the live SQLite database local and create
consistent snapshots for backup. Configure Dropbox separately from QR-provisioned
SFTP/FTPS hosting credentials. Credentials must never go in the website bundle.

The native **Changes** tab has separate **Unsaved** and **Unpublished** views,
with before/after values for changed content, ordering, and homepage selection.
Added or removed pages and news show only their title and date, without their
content, sections, or asides. Page and news changes also show the record ID.
Unsaved compares against the last local save. Unpublished
compares against the last successful publish recorded on this device; saving,
exporting, or a failed upload does not advance that baseline. The published
snapshot survives app restarts. Older installations start tracking after their
first successful publish from a version that supports snapshots. These are local
content comparisons, not a check of server files or hosting settings.

**Templates** opens selectable template source code, with a **Preview** action.
Image/video details open in a full-screen native view. Image import optionally
accepts a maximum pixel width and JPEG quality percentage, with selectable common
presets and custom values. Advanced mode starts both presets at **None**;
Simple defaults to 400 pixels and 85% JPEG quality. Leave both blank for
the existing import behavior. Resizing changes the saved file, preserves aspect
ratio and orientation, and never enlarges smaller images. Resized JPEG defaults
to 85% quality; PNG remains lossless with transparency. Animated images must be
imported without optimization. Original photos are not modified.
Building blocks remain available to the renderer but are not a native menu tab.
Simple page inserts its header and content without a page wrapper. Other page
layouts use nonsemantic containers for layout; news and article templates retain
article wrappers. The document head, logo, and navigation are generated separately
from the selected content template.

## Content and phone media

`website/src/content.json` is the example/first-install seed. Pages have `title`,
`menuTitle`, `slug`, `content`, and an ISO `date`, plus optional ordered `children`,
`sections`, and `asides`. Subpages become `/parent/child/`. News has `title`, `slug`,
`date`, `shortContent`, and long `content`, plus optional sections/asides. The news index at
`/news/` contains the three newest items as articles; all news items have full
pages at `/news/YYYY-MM-DD/slug/`, including older items outside that listing.
Sections have heading/content and optional asides. Asides have content and an
optional heading. Internal IDs are stable; sections/asides belong to exactly one
owner and are deleted with it. Deleting the last item does not restore the seed
on the next launch.

Choose **Use as homepage** in a native page/subpage form to select `index.html`.
Existing projects default to their first top-level page. The selected page
keeps its named URL and layout. Removing it falls back to the first remaining
top-level page. The chosen homepage is omitted from the menus. A home-linked logo
sits to the left of the main menu. The main menu lists other top-level pages, marking the current page
or its top-level ancestor with `class="active"`. News is active on its listing
and article pages. Pages with subpages have an
`<aside>` navigation shared by their descendants, with the current link marked
active and `aria-current="page"`.

Publishing validates FTPS certificates and asks you to compare and trust a new
SFTP server's SHA-256 host-key fingerprint before sending the password. Changed
trusted keys stop publishing. Plain FTP requires confirmation and is unencrypted.
Matching generated files are overwritten and `index.html` is uploaded last.
Unrelated remote files, previously published pages, and unused remote media are
not deleted automatically. A failed upload can leave a partially updated website;
retry after correcting the connection or permissions.

In Hosting, set **Public URL prefix** and **Upload subfolder** separately, then
choose **Save publishing settings**. Use `/` for a domain-root website and leave
the upload subfolder blank to publish directly into the QR's saved destination.
For a nested deployment, the public prefix is its full public path (for example
`/site/`), while the upload subfolder is relative to the saved FTP/SFTP directory
(for example `site`). Homepage links show and store `/`; publishing resolves them
and older `index.html` links to the configured prefix. A blank public prefix uses
relative home links. Publishing uses these settings; exports and previews stay portable. Settings are kept with
secured hosting credentials, not in the content database or exported website.
The Rsbuild catalog at `/` is separate from its generated example site, whose
homepage is `/home.html`; example logos return there rather than to the catalog.

Content supports headings, paragraphs, bold, italic, strike, underline, emphasis,
bulleted/numbered lists, blockquotes, and links. **Link** accepts a URL or a
page/subpage choice; use **Link** while on an existing link to edit it or choose
**Remove link** inside its dialog. Phone images and videos are inserted
as `<figure>` containing `<img>` or `<video><source>`, with optional `<figcaption>`.
Text inside a figure is kept in one `<figcaption>`. Select a caption to edit it,
or select the picture/video and use Backspace or Delete to
remove the whole figure without deleting the stored media.
The figure dialog provides an optional file basename before opening the phone picker.
Leave it blank for an automatic name; extensions follow the imported format, and
duplicate names get numeric suffixes such as `coast-2.jpg`. Basenames accept up to
80 letters, numbers, hyphens, or underscores, starting with a letter or number.
The image dialog also provides alternative text. Imported files are copied into
app-owned media storage; content refers to portable `media/<filename>` paths,
never temporary picker URLs. HEIC/HEIF images are converted to JPEG where native
decoding supports them. Compatible files are otherwise preserved, including
metadata; check private photo metadata before publishing. Video codec support
depends on the viewer's browser, and MP4 is recommended.

Rich text is sanitized with DOMPurify, retaining only the supported tags and
controlled local media attributes. Scripts, events, styles, embedded frames,
remote URLs, data URLs, and parent-directory paths are removed. A page title
provides the main heading; content-level H1 is normalized to H2.

`shared/storage.sql` describes native pages, news, sections, asides, media, and
seed metadata. Android uses `SQLiteOpenHelper`; iOS uses SQLite3 in Application
Support, with the database outside the WebView's readable website/media folder.
Content saves are transactional, ownership uses cascading foreign keys, and
future unsupported database versions fail rather than deleting local work.

Run browser interaction/media/reflow tests with:

```sh
cd website
npx playwright install chromium
npm run test:editor
```

`npm test` covers rendering, sanitization, shared SQL constraints, packaging, and
the existing hosting/publisher tooling. A hidden local rendering engine shares the
website generator/sanitizer; it is not editor UI. Android's unit tests cover actual SQLite
seeding, reopening, rollback, media storage, and export. iOS compilation and
actual phone-picker testing require macOS/devices and have not been run on Linux.

## Hosting connection QR (development only)

Run `npm run dev --workspace website` and open
`http://127.0.0.1:4173/provision.html`. Create `website/hosting.local.json` using
`website/hosting.example.json` as the format, then fill in the dedicated hosting
account. The page loads this file and generates its QR automatically. Use
**Reload local connection JSON** after editing the file. There is no credential
editor or manual-entry form; the local JSON file is the only source.
Use `python3 run.py credentials` to configure it from the local console. Passwords
are entered without echo, confirmed when changed, and saved atomically with private
permissions. Blank entries retain current values. If you change the development
FTP login, restart `run.py server` so its account matches the new QR.

The local credentials file is gitignored. It is not imported into JavaScript or
copied to the website output. Rsbuild reads it at request time and embeds a
server-generated SVG QR directly in the private-LAN setup page. No browser
JavaScript or canvas is needed to display the QR. Nothing is sent
to an external backend, logged, or stored in browser storage. Reloading the page
generates a new QR from the file. Never share a credential
QR publicly. Prefer SFTP or explicit-TLS FTPS; plain FTP is unencrypted.
You can also open `/provision.svg` to view the QR image directly. Both routes
are private-LAN-only and no-cache; the generated image is never written to disk.

In the app's **Hosting** tab, choose **Read QR from picture** to import a saved
QR or screenshot, or **Take a QR picture** to use the camera. Pictures are decoded
on-device and never added to website media or uploaded. Credentials are stored in
iOS Keychain or encrypted with Android Keystore, never in the content database.
Importing a QR does not connect to the server. Its versioned JSON contract is:

```text
version: 1
kind: "webeasy-publish"
connection:
  protocol: "sftp" | "ftps" | "ftp"
  host: hostname or IP address (not a URL)
  port: integer
  username: string
  password: string
  directory: absolute remote path
```

The apps validate this schema and reject other QR formats or future versions.
Future publishing must verify SSH host keys or TLS certificates. The provisioning
page, QR-generation library, and local credentials are excluded from production
website and native bundles; only the shared QR validator is packaged.

## Task runner and development hosting

Like the reference projects, `run.py` accepts a task followed by `KEY=VALUE`
environment settings and forwarded arguments:

```sh
python3 run.py setup
python3 run.py server HOST=192.168.1.2
python3 run.py ts
python3 run.py ftp
python3 run.py credentials
python3 run.py build
python3 run.py test
python3 run.py android
python3 run.py ios
python3 run.py publish --help
```

`server` (also named `parallel`) runs FTP and Rsbuild together and stops both on
Ctrl+C or if either service fails. It defaults to `192.168.1.2`, FTP port `2121`,
and HTTP port `4173`; change them with `HOST`, `FTP_PORT`, and `RSBUILD_PORT`.
`setup` installs npm dependencies and the development/publishing Python packages
in `.venv-dev-host`.

FTP is jailed to the gitignored `dev-host/` directory. Uploads are served at
`http://192.168.1.2:4173/host/`, including video range requests and APK/IPA downloads.
The app-store subdirectory is `/host/store/`. Hidden staging files and symlinks
outside the host root are not served. These development files are never copied
into production website/native bundles.

The FTP runner creates a random password in gitignored `website/hosting.local.json`
with restrictive permissions if the file does not exist. It never overwrites an
existing configuration for another host. Open `/provision.html` on this machine or
another private-LAN device
to scan its QR. Anyone on that LAN can view the credential QR. Public-network
clients are rejected using the socket address, not forwarded headers; same-origin
and Host checks still apply. Plain FTP is only for trusted LAN testing; do not
expose this development server to the internet.

To test app-store uploads against this development FTP host, use the publisher's
`--upload --allow-insecure-ftp --allow-http-development` flags and
`--public-url http://192.168.1.2:4173/host/store`. HTTP is only allowed for local/private
addresses with the explicit development flag; use HTTPS for real distribution.
Some iOS sideload tools may require HTTPS even during testing.

## Publish apps to your webhost

`scripts/publish_store.py` prepares an Android download page/metadata and an
iOS SideStore/AltStore source. It uses the same local hosting JSON as QR setup.
The default remote location is its `directory` plus `/store`; override this with
`--remote-directory`. Set `--public-url` to the matching public HTTPS directory.

After building the apps and supplying a PNG icon:

```sh
npm run build
python3 scripts/publish_store.py \
  --apk apps/android/app/build/outputs/apk/debug/app-debug.apk \
  --ipa /path/to/Webeasy.ipa \
  --icon /path/to/app-icon.png \
  --android-version 0.1.0 --android-build 1 \
  --public-url https://your-host.example/store
```

This only prepares `dist/store/`; it does not read credentials or connect.
Review the files, then repeat with `--upload` to publish. The script uploads
versioned APK/IPA filenames, the icon/CSS, `android.json`, a download page,
and finally `source.json`. Each file uses a temporary remote name followed by
rename. Existing release files are not deleted; the source advertises the current
iOS release only. SFTP updates require server support for atomic replacement.

For SFTP, install the optional upload dependency in a virtual environment:

```sh
python3 -m venv .venv-publish
.venv-publish/bin/pip install -r scripts/requirements-publish.txt
```

Use `.venv-publish/bin/python` to run the publisher. It verifies SSH keys using
trusted `known_hosts` entries (or `--known-hosts`); it never silently trusts a new
server. Verify the host fingerprint through your hosting provider first.
FTPS uses Python's standard library and verifies TLS certificates. Plain FTP is
disabled unless you explicitly pass `--allow-insecure-ftp`.

The **Build sideload apps** workflow produces a development APK and an unsigned
device IPA for SideStore/AltStore signing. Android's debug signing key on ephemeral
CI runners is not stable: for public Android updates, supply a release APK signed
with your persistent key. Pass its actual versionName/versionCode to the publisher.
The publisher derives iOS version/build metadata from the IPA and checks its bundle
identifier. This is webhost sideload distribution, not Apple App Store/Google Play
submission. Verify the public URLs and source in SideStore/AltStore before sharing.
