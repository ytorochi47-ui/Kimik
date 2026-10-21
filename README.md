# WebtoonLite — Android Webtoon Translator (English → Indonesian)

**Target:** vivo Y20 2021 / Android 11 / RAM 4 GB. **Price target:** Rp0 via Gemini API Free Tier (quota limits apply). **Current status:** Java source + cloud build workflow prepared; actual APK compilation, API credential validity, and on-device behavior remain **UNVERIFIED**.

## Features

- Floating **T** button and drag-to-select dialogue *in any order* — no requirement to translate every speech bubble.
- Captures one screen frame per tap via MediaProjection (with Android permission); full-screen Latin OCR with Google ML Kit plus crop fallback.
- Sends selected text and limited nearby text/previous selections as context for natural Indonesian translation. Text outside selection is **context only**.
- Uses Google Gemini API `gemini-3.5-flash-lite` with direct HTTPS request, no additional translation server.
- Small translation popup with scroll support for long dialogues, **Copy** and **Close**.
- Encrypted user-entered API key on Android Keystore; **NO KEY IS EMBEDDED IN APK, SOURCE OR WORKFLOW**.
- **Tes koneksi Gemini** button confirms API connection after installation; a test consumes one quota request.
- RAM-friendly: no local LLM, no continuous OCR; screenshot released after recognition, no image upload; in-session cache.

## Option A — Build with GitHub Actions (free, no Android Studio required)

> You need a GitHub account. Use the workflow provided in `.github/workflows/build-android-apk.yml`.

1. Create a new **private** GitHub repository (or public if you prefer; **never put a credential into it**). A new repository should be empty (no auto-generated README or .gitignore).
2. On a computer, extract the source ZIP and push the **contents** of the `WebtoonLite` folder, including the hidden `.github` directory, onto the repository's `main` branch. You can use GitHub Desktop or `git`:

   ```bash
   git init
   git add .
   git commit -m "Initial WebtoonLite"
   git branch -M main
   git remote add origin https://github.com/YOUR_USER/YOUR_REPO.git
   git push -u origin main
   ```

3. Go to your GitHub repository > **Actions** > **Build Android APK (Free)** > **Run workflow**. A push to `main` will also automatically trigger the build if GitHub Actions is enabled.
4. After the workflow completes successfully, click the workflow run > **Artifacts** > **WebtoonLite-Android11-APK**. This ZIP artifact contains `WebtoonLite-debug.apk` and its SHA-256 hash.
5. Transfer the APK to your vivo Y20 and install it (allow installs from that trusted file manager/browser if Android asks). This is a **debug-signed installable APK**; it is not a Play Store release.
6. Open the installed app. Enter your personal **Gemini API key** into the masked field, choose **Tes koneksi Gemini**, then **Mulai translator**. Approve display-over-apps and screen capture; open webtoon; tap the floating **T** and select difficult text.

The workflow uses Android SDK 35, JDK 17, Gradle 8.10.2 and Android Gradle Plugin 8.7.3. It builds with `gradle :app:assembleDebug` and needs **no workflow or repository secrets**.

GitHub's hosted runners and Actions have usage limits, including for private repositories; check your remaining free minutes if you have other workflows.

## Option B — Android Studio

Install JDK 17, Android SDK Platform 35 and Android Studio on your computer. Open the folder as a Gradle project and configure Gradle 8.10.2; this archive does not include a wrapper JAR. Run `gradle :app:assembleDebug` (or **Build APK(s)**) after the toolchain and Gradle are installed. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Security / limitations

- An API key **already pasted into a chat** should be considered disclosed. Create a replacement in [Google AI Studio](https://aistudio.google.com/api-keys), revoke the old key, and paste the new one **inside the app on your phone only**.
- Hardcoding the key in an APK would make it extractable. A per-device Keystore protects it *at rest*, but a direct client API still cannot provide the security guarantees of a server-side proxy. Recommended only for personal use; do not distribute an APK together with a key.
- To avoid monetary charges, keep the Gemini project on **Free Tier without paid billing**. If the API sends a quota-limit error, wait until the limit resets; the app does not automatically enroll in paid services.
- Free-Tier requests may be used by Google to improve products under the applicable terms. The screenshot remains on the device; selected OCR text, limited nearby OCR text and up to three prior user-selected translations are transmitted to Gemini.
- OCR is **English/Latin only**; strange lettering, unusual fonts, curved text and small text can fail. The app cannot capture DRM-protected/secure surfaces.
- The app asks for screen capture permission and has a visible ongoing capture notification while active. You can stop the service from the main app.
- UI coordinates, screenshots and background-service behavior can vary by Android manufacturer, browser, orientation and notch. On-device QA is essential.
- No automatic translation while scrolling, Japanese/Korean OCR or changing text inside balloons in this MVP.

## Technical references

- https://ai.google.dev/gemini-api/docs/api-key
- https://ai.google.dev/gemini-api/docs/pricing
- https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/build/releases/agp-8-7-0-release-notes
- https://github.com/avadhesh1017/MangaLens
- https://github.com/navidseyedain/ALSTMobile

## Validation performed here

- All **five Java files** parsed using the JDK 21 compiler frontend (syntax only, no Android classpath).
- `AndroidManifest.xml` parsed successfully as XML.
- Workflow YAML parsed successfully as YAML.
- **NOT** tested: Android SDK compilation, runtime on vivo Y20, API key authentication, OCR quality, latency, memory, or overlay behavior.
