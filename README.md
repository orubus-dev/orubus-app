# Orubus AI — Android client

The official Orubus AI app: `app.orubus.ai`.

- **duck.ai-powered chat** as the main brain (free, separate interface) — same soul + memory as the bots
- **Soul Units app** — wallet, tasks, ads, packs (live web surface)
- **Requests wall** — propose, vote, comment
- **Legal-first onboarding** — agreement popup, then paste your `GENJ-XXXX` token from `/genjutsu` — no accounts, no passwords
- **Continuous updates** — GitHub Releases is the distribution channel: the app checks `releases/latest` on launch and offers the new APK in one tap. Bump the `VERSION` file → workflow builds, signs, publishes.

## Shipping a release

1. Edit `VERSION` → `x.y.z <versionCode>` (versionCode must increase).
2. Commit + push (or run the **android** workflow manually).
3. The signed `Orubus.apk` lands on GitHub Releases:
   `https://github.com/orubus-dev/orubus-app/releases/latest/download/Orubus.apk` (stable URL).

Users: send `/download` to [@orubus_bot](https://t.me/orubus_bot) — the bot ships the file with install steps.

## Signing

The release keystore lives in GitHub Actions secrets (never in git):

| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | base64 of `orubus-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `orubus` |
| `ANDROID_KEY_PASSWORD` | key password |

Build locally without secrets → unsigned APK (assembleRelease still succeeds).

## Tech notes

- Package `app.orubus.ai`, minSdk 24 / targetSdk 34, Kotlin, **zero external dependencies** (framework views + org.json only) — small APK, nothing to break.
- Backend: the deployed Vercel API (`orubus-ai.vercel.app`) — the app is just another face of the same identity system.
