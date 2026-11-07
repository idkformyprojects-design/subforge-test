# SubForge — Privacy Policy

_Last updated: 7 November 2026_

SubForge is an Android app that makes subtitles for videos using Google's Gemini AI with **your own Google API key**.
This policy explains what happens to your information when you use it.

## The short version

- **Your video never leaves your phone.**
- SubForge sends only what is needed to make subtitles, **directly to Google's Gemini service**, using your own key.
- SubForge has **no server, no account, no ads, no analytics and no tracking**. The developer of SubForge receives
  **nothing** from your use of the app.

## What is sent, and to whom

When you start a job, SubForge sends the following **directly from your phone to Google's Gemini API**
(`generativelanguage.googleapis.com`), using the API key you entered:

| What | Why |
|---|---|
| The **sound** of your video, cut into 5-minute parts (compressed audio, about 1 MB per 5 minutes) | So Gemini can write and translate the subtitles |
| Your optional **"About the video"** text (for example a title or character names) | So names are spelled correctly |
| The **subtitle text**, when you translate an existing subtitle track or use Double-check | So Gemini can translate or correct it |
| Your **API key** | So Google knows whose free (or paid) quota to use |

Google's handling of this data is governed by the
[Gemini API Additional Terms of Service](https://ai.google.dev/gemini-api/terms) and
[Google's Privacy Policy](https://policies.google.com/privacy).
**Please note:** on Google's **free** tier, Google states that it may use the content you send to improve its
products, and that human reviewers may read it. Do not use SubForge's free mode for private or confidential videos.

## What stays on your phone

Stored only in SubForge's private app storage on your device, and never sent to the developer:

- your API key and settings,
- your subtitles and History,
- temporary audio parts (deleted automatically when a job is fully finished, or with "Clear saved audio").

This data is **not** included in Android backups. Uninstalling SubForge deletes all of it.

## Permissions

| Permission | Used for |
|---|---|
| Internet | Talking to Google's Gemini API |
| Notifications | Showing progress and "done" messages |
| Foreground service, wake lock | Continuing work in the background while the phone is locked |

SubForge opens videos only through Android's file picker, so it **cannot see any other files** on your phone.
When you start the key set-up, SubForge looks at the clipboard only to find a copied Google API key, and then clears it.

## Reports

If you use **"Report this line"** or **"Report a problem"**, your e-mail app or web browser opens with a pre-filled
report. Nothing is sent unless you send it yourself. A report contains only what you see in it (for example the
subtitle line you reported and the app version).

## Children

SubForge is not directed at children. Creating a Google API key requires following Google's own age requirements.

## Changes

If this policy changes, the new version will be published at this address with a new date.

## Contact

Questions or requests: open an issue at
<https://github.com/idkformyprojects-design/subforge-test/issues>.
