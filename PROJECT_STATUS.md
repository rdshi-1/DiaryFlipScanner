# Project status

## Completed in this MVP

- Camera permission and live preview.
- Hands-free page-turn state machine.
- Stability and sharpness checks.
- Automatic high-resolution image capture.
- Two-page split using the centre guide.
- Duplicate-spread suppression.
- Vibration/beep confirmation.
- Optional continuous phone light with automatic switch-off when scanning finishes.
- Session folders and page numbering.
- Background upload and transcription queue.
- Private token-protected transcription server.
- Editable review and share screen.

## Validation completed here

- All Android XML resources parse successfully.
- The Python backend passes Python bytecode compilation.
- Kotlin sources were parser-checked; a full Android compile requires the Android SDK and Maven dependencies.

## Not yet device-tested

Camera thresholds vary by phone, lighting, distance and handwriting. The first real-device test should concentrate on:

1. Whether one page turn always exceeds the motion threshold.
2. Whether the phone focuses before the stable-frame counter expires.
3. Whether the physical diary binding aligns with the centre crop.
4. Whether similar-looking pages are incorrectly treated as duplicates.
5. Transcription accuracy on the user's actual handwriting.


## v0.6 additions
- Original spreads retained for later centre correction.
- Review-page re-splitting from the original two-page image.
- Adjustable split position and omitted gutter width.
- A4 PDF export in reviewed page order. Version 0.7 uses Android Create Document and a streaming JPEG-to-PDF writer.


## v0.7
- Replaced crash-prone FileProvider PDF sharing with Android Create Document.
- Added streaming JPEG-to-PDF generation with bounded memory use.
- Added clear failure messages and cleanup of incomplete PDF files.
