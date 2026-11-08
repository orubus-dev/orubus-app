Orubus AI for Android — v3.7.4 (code 13)

**the fix that matters: ads are live.**
the app was still dialing the old Vercel host — dead since the move to Cloudflare
Pages — so every wallet, chat and ad call died at the door. v3.7.4 points the
whole app at orubus-ai.pages.dev:

- rewarded ads work again: watch an ad → +20 Soul Units, up to 4/day
- the SU wallet, tasks and the shop reconnect (they were quietly offline too)
- terms URL updated to the live host

Install: open the APK, allow "install unknown apps" if asked, install over any
previous version — or update straight from the in-app updater (full APK or the
12→13 delta, both byte-verified before install).
