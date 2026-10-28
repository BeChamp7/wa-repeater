# Repeater

A floating bubble over WhatsApp. Tap it, type text or pick an emoji, set a delay, hit **Start** — it keeps sending into the open chat until you tap the bubble again.

No dependencies, no network, no ads. Kotlin, minSdk 26 (Android 8), targetSdk 35.

**Download the latest build:** https://github.com/BeChamp7/wa-repeater/releases/latest/download/Repeater.apk

## Build & install

1. Open the `WaRepeater` folder in Android Studio and let Gradle sync.
2. Plug in your phone (USB debugging on) and press **Run**, or **Build → Build APK(s)** and sideload `app/build/outputs/apk/debug/app-debug.apk`.

## First-time setup (on the phone)

1. **Unlock restricted settings:** Repeater → *Open App info* → ⋮ → **Allow restricted settings** (Android greys out overlay/accessibility for apps installed outside the Play Store).
2. **Allow** "Display over other apps".
3. **Turn on** *Repeater (WhatsApp)* in Accessibility settings.

## Using it

- Open a WhatsApp chat → the green ↻ bubble appears (only while WhatsApp is on screen).
- Tap it → type a message or tap an emoji → set the delay (1–30 s, 0.5 s steps) → optional "stop after N" → **Start**.
- The bubble turns red and counts sends. **Tap it to stop.** Drag to move it.
- It also stops by itself if you leave WhatsApp/the chat, or if it can't find the message box or Send button 3 times in a row.
- Works with WhatsApp and WhatsApp Business.

## How it works

`RepeaterService` (an `AccessibilityService`) does everything:

- **Bubble** — `TYPE_ACCESSIBILITY_OVERLAY` window, shown only when a WhatsApp app window is on screen.
- **Panel** — `TYPE_APPLICATION_OVERLAY` (so the keyboard works); falls back to an accessibility overlay if overlay permission is off (emoji buttons still work then).
- **Loop** — finds `com.whatsapp:id/entry` → `ACTION_SET_TEXT` → waits 250 ms → checks the text actually landed → clicks `id/send`. Falls back to "first editable field" / a node described "Send" if WhatsApp renames its views.

If a WhatsApp update breaks it, the view IDs in `findInput()` / `findSend()` are the place to look (use Android Studio's Layout Inspector or `uiautomator dump` to find the new ones).

## Heads-up

- Rapid automated sending can get a WhatsApp number flagged or temporarily banned. The 1 s minimum is deliberate; keep runs short.
- The accessibility service can technically see the screen. This code only ever reads WhatsApp's windows, stores only your last message/delay/limit locally, and has no internet permission.

## Signing

Release builds are signed with the permanent key in `signing/` so new builds install as updates over old ones.
The key and its password are committed because this repo can't use GitHub Actions secrets from where it was set up.
Anyone with this repo could sign an APK with the same key, so only install Repeater builds from this repo's Releases.
To lock it down: move the password into a repo secret, read it in `app/build.gradle.kts` via `System.getenv`, and delete it from `signing/signing.properties`.
