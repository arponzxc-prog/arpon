<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/523fe994-fda0-40b0-bf8d-432450c7f62e

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Background builds (Step 6) & Editor build button (Step 7)
- `builder/BuildKeepAliveService` (foreground service, type `dataSync`) keeps the process alive while a cloud build runs; `BuildEngine` is now a process-wide singleton (`BuildEngine.getInstance`) so leaving/closing the UI never orphans a build. A result notification is posted when the build finishes.
- `MainActivity`: the Webapp build-customize dialog (and AI-fix dialogs) are now composed on the full-screen Editor too (they were skipped by an early `return`).

## Changelog 1.1.0
- **AI auto-failover** (`data/ai/AiFailover.kt`): typed `ApiHttpException(code, body, retryAfter)`; 429/402/401/403/404/5xx/timeout shift to the next backup model. Cooldowns: 429 → `Retry-After` or 60 s, 401/402/403/404 → 10 min, 5xx/timeout → 30 s. Toggle and backup order in Settings → API. Failover happens inside one request, before any agent plan runs, so file operations are never repeated.
- **Identity** (`data/ai/AiIdentity.kt`): "your name" → *Nexa Ai*, "who made you" → *Developer Arpon*, answered locally without an API call, plus a system-prompt fallback rule.
- **Usage dashboard**: per-model requests / failures / failovers / estimated tokens (numbers only, never content).
- **Editor**: project-wide search & replace (regex / case / whole word), Emmet (HTML + CSS subset) from the ⋮ menu.
- **Room**: `exportSchema = true` (schemas in `app/schemas`), `NexaMigrations` registry + test that every version step has a migration.
- **Background builds**: foreground service keeps cloud builds running when the app is closed.
- `GitHubScreen.kt`: onboarding/empty-state composables moved to `GitHubOnboardingComponents.kt`.

## Testing
`./gradlew assembleDebug` and `./gradlew test`
