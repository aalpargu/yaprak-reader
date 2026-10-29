# Yaprak

A personal Android reading library built with Kotlin and Jetpack Compose. It keeps imported books and reading records on the device, with format-specific readers, bookmarks, notes, search and optional source-based book assistance.

[Türkçe](README.tr.md)

**In development.** This public snapshot is based on the saved 0.183.0 source (`546e43464eb54f7a87435d57bda3029bbf7523e4`). Development continues separately. It is not a Play Store release.

## Reading and library features

- Import books through Android's document picker and retain the original files.
- Read EPUB, PDF, TXT, FB2, basic DOCX and CBZ/CBR, with different capabilities for each format.
- Restore reading position, save bookmarks/notes and adjust supported reading preferences.
- Search an explicitly built local text index and return to supported source locations.
- Back up and restore the library and reading records with versioned validation.
- Prepare questions, summaries and study prompts from supported extracted sources through an optional server.

CBR/CBZ are image readers, not OCR/text-search sources. DOCX support is a reading conversion and does not reproduce every Word layout feature. Source selection and AI coverage also vary by format.

## Organization

`app/` contains the Android application. UI/ViewModel code is separated from Room persistence, file handling, format conversion and reader integration. `app/schemas/` retains Room schema history. `buildSrc/` contains build helpers and their tests.

Readium handles reflowable reading, while PDF and comic features use their own supported paths. Temporary converted/cached content is kept distinct from the original imported book. Coroutine cancellation and source validation guard long-running operations and stale results.

`server/` is a small optional Node gateway for book assistance. `tests/` contains additional source-level checks. Android unit/instrumentation tests are under `app/src/test` and `app/src/androidTest`.

## Build

Use JDK 17 and an Android SDK compatible with the versions pinned in the Gradle files. Minimum Android version is 8.0 (API 26).

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat`. Set `ANDROID_HOME` or create a local `local.properties` with your SDK location. Machine paths and signing keys are not included.

## Optional book-assistance server

The reader works without the server. The gateway uses `OPENAI_API_KEY`, `OPENAI_MODEL` and `YAPRAK_ACCESS_TOKEN` from its environment; provider credentials do not belong in the APK. `PORT` defaults to 8787 and the included launcher listens on loopback.

```bash
cd server
npm test
npm start
```

Configure a suitable authenticated HTTPS deployment before connecting a physical device. User-selected/extracted source text is sent for a request; the feature does not establish that an entire book has been read or covered.

## Verification and remaining work

The saved 0.183.0 development record reports 179 targeted host tests, 70 targeted Android 35 tests, lint and debug/release builds for that candidate. Those were scoped candidate checks rather than the full set of every workflow, and they are not presented as a fresh run of this public copy.

Physical-device accessibility, large libraries, broader real-world format samples and live-provider acceptance remain separate checks. Current development includes further reader-position work. The test sources are included so the published code can be inspected and checked independently.
