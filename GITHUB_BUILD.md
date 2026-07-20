# Build DiaryFlip using GitHub Actions

The repository must have `settings.gradle`, `build.gradle`, `app`, `backend`, and `.github` at its top level. Do not upload the enclosing folder as an extra directory.

1. Create an empty GitHub repository whose default branch is `main`.
2. Unzip `DiaryFlipScanner.zip`.
3. Open the inner `DiaryFlipScanner` folder.
4. On macOS press `Command + Shift + .` so the hidden `.github` folder is visible.
5. In the GitHub repository choose **Add file → Upload files**.
6. Drag every item from inside the inner `DiaryFlipScanner` folder onto the upload page, including `.github`.
7. Commit the upload to `main`.
8. Open **Actions → Build Android APK**.
9. Choose **Run workflow → Run workflow**. A push to `main` may already have started a build automatically.
10. Open the completed workflow run and download the `DiaryFlip-debug-apk` artifact.
11. Unzip the downloaded artifact to obtain `app-debug.apk`.

The APK is a debug build suitable for personal testing. It can capture and save page images immediately. Automatic AI transcription additionally requires the backend described in `README.md`.
