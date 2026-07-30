# DiaryFlip — hands-free handwritten diary scanner

DiaryFlip is an Android MVP designed around one interaction: mount the phone above an open diary, tap **Start scanning**, and turn the pages. The app detects a page turn, waits for the new spread to stop moving and become sharp, captures it automatically, splits it into left and right page images, and vibrates once when it is safe to turn the next page.

## What is implemented

- Native Android camera preview using CameraX.
- Automatic motion/stability detection; no shutter button per page.
- Sharpness check before capture.
- High-resolution still capture rather than low-resolution video-frame extraction.
- Centre-gutter split into two page images.
- Duplicate-spread suppression.
- One vibration and quiet beep after each successful spread.
- Optional continuous phone light/torch while scanning, with an on-screen toggle.
- Background transcription queue using WorkManager.
- Private FastAPI transcription backend using image input and structured JSON output.
- Review screen with numbered pages, editable transcription, delete, drag-to-reorder, manual edge cropping, original-spread re-splitting, save and share.
- One-tap PDF export with one corrected page per PDF page, following the saved review order.
- Captures still work when transcription is not configured.

## Important MVP limitation

The automatic first split still starts near the centre of the photograph, but the retained original spread can now be re-split from Review by moving the centre line and changing the omitted gutter width. Full curved-page dewarping is not yet included. Use a phone stand, even lighting, landscape orientation and a dark surface beneath the diary.

## Open the Android project

1. Install a current Android Studio version.
2. Open the `DiaryFlipScanner` folder.
3. Allow Gradle sync to finish.
4. Connect an Android phone with USB debugging enabled.
5. Press **Run**.

The project compiles against Android API 36, targets Android 15/API 35, and supports Android 8.0/API 26 and later.

## Build an APK

In Android Studio choose:

`Build` → `Build App Bundle(s) / APK(s)` → `Build APK(s)`

The debug APK is normally created at:

`app/build/outputs/apk/debug/app-debug.apk`


## Build without installing Android Studio

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. It installs Gradle 8.11.1 directly, builds the debug APK, and uploads it as `DiaryFlip-debug-apk`. See `GITHUB_BUILD.md` for click-by-click instructions.

## Run the transcription backend locally

The API key belongs only on this backend. Do not add it to the Android project.

```bash
cd backend
cp .env.example .env
# Edit .env and add OPENAI_API_KEY and a private DIARYFLIP_TOKEN
docker compose up --build
```

The health check is available at `http://localhost:8000/health`.

For a physical phone on the same Wi-Fi network, use your computer's LAN address in DiaryFlip settings, for example:

`http://192.168.1.40:8000`

Also enter the same `DIARYFLIP_TOKEN` used in `.env`.

For use away from home, deploy the `backend` folder to a HTTPS-capable container host, set the three environment variables there, and enter its public HTTPS URL in the app once.

## Scanning procedure

1. Put the phone in landscape orientation on a stable overhead stand.
2. Make sure both pages fill the white guide, with the binding on the dashed centre line.
3. Avoid glare and strong shadows near the binding.
4. Tap **Start scanning**.
5. Tap **Light off** to turn on the phone light when the room is dim. The button changes to **Light on**. Avoid using it if it creates glare on glossy pages.
6. Hold the first spread still until the phone vibrates.
7. Turn one page and remove your hand.
8. Wait for the vibration before turning the next page.
9. Tap **Finish**, then open **Review pages**. The light switches off automatically.
10. In Review, hold **Move** and drag pages into order, or tap **Delete** to remove mistakes. Page numbers and shared text update automatically.
11. Tap **Re-split spread** to correct the diary centre from the original two-page photograph, or **Adjust crop** to trim one page.
12. Tap **Export PDF** to create and share a PDF in the current reviewed order.

## Privacy

- Page files are stored in the app-specific DiaryFlip folder on the phone.
- The server reads each uploaded image into memory and does not deliberately save it.
- `store=False` is used for the model request.
- Read the API provider's current data controls before scanning highly sensitive diaries.
- A public backend should always use HTTPS and a long `DIARYFLIP_TOKEN`.

## Tuning automatic capture

The main thresholds are in:

`app/src/main/java/com/example/diaryflip/scanner/StabilityAnalyzer.kt`

- `motionThreshold`: lower values detect smaller movements.
- `sharpnessThreshold`: higher values demand a sharper image.
- `stableFramesRequired`: higher values wait longer before capture.
- `cooldownMs`: minimum delay between capture attempts.

Duplicate sensitivity is in `MainActivity.kt` at `duplicateDistance < 2.2`.

## Suggested next improvements

- Detect the four corners of each page and perspective-correct them independently.
- Curved-page dewarping near thick diary bindings.
- Detect blank first/last sides rather than treating every spread as two pages.
- Retry and progress indicators for each transcription.
- Export directly to DOCX and add an optional searchable text layer to PDFs.
- On-device OCR fallback for offline use.

## Version 0.4 scanning feedback

After each successful two-page capture, the main status changes to **Next page ✓** and remains there until the app detects that the next page turn has begun. The page counter continues to show the total number of saved pages.


## Version 0.6 review and export

Each original two-page photograph is retained automatically. From either page in Review, **Re-split spread** opens the original photograph with a movable centre line and adjustable gutter width; saving refreshes both existing page images without restoring a page that was previously deleted. **Export PDF** creates an A4 PDF containing the corrected page images in the current saved order and opens Android's save/share chooser.
