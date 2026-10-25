# Orubus AI — Android client (v2 "Liquid Glass")

The official Orubus AI app: `app.orubus.ai`.

- **The request engine runs on YOUR device** — duck.ai is called with the user's own IP (the residential lane DDG accepts), so "brain lane is packed" cannot happen here. The duck.ai fe-signals challenge is solved on-device by `assets/solver.html` (a faithful port of the server solver) running in a 1px offscreen WebView.
- **Same Soul Units economy** — every message spends SU exactly like every other Orubus surface (`appturn`/`appdone`/`appfail` on `/api/chat`: spend → single-use HMAC turn token → archive or refund). Free for the project, never free for the user.
- **Liquid glass brutalism UI** — deep-ink base with drifting brand orbs, translucent glass panels with refractive rims + specular streaks, heavy uppercase type. Chat-first: no split bottom bar.
- **Multi-chat** — hamburger drawer: chats on top, name + credits (meter, bonus badge) at the foot. Long-press a chat to delete.
- **Intents, not commands** — type "open shop", "new chat", "how many credits", "remember my name is …", "catch me up". Unmatched text flows to the AI.
- **Settings (top right)** — shop, requests, inbox (admin announcements with unread badge), legal, about, app version, identity/log out.
- **Google AdMob** — rewarded ads earn SU (drawer button → `adtoken` → show → `ad` claim), banner rides the shop. IDs live-tunable from Mission Control; the manifest ships Google's TEST application id until the real AdMob account is wired.
- **Legal-first onboarding** — agreement popup, then paste your `GENJ-XXXX` token from `/genjutsu` — no accounts, no passwords.
- **Continuous updates** — GitHub Releases is the distribution channel: the app checks `releases/latest` on launch. Bump the `VERSION` file → workflow builds, signs, publishes. Duck model slugs + AdMob ids stream from server state, so most fixes ship without a new APK.

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
