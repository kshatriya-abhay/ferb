# Ferb's privacy note

Ferb is an assistant that does things on your Android phone. It has no internet access: the app's network permissions are removed, and Ferb has no analytics, ads or accounts.

## What stays on your phone

- **Your requests.** Needle 3 picks the action and Whistle hears you, both on the phone. Gemini Nano, where you use it, runs on the phone too, inside Android's AICore.
- **What Ferb keeps.** Your settings and reminders stay in Ferb's own storage. Notes and lists go to the folder you pick. The chat is kept only while Ferb is open, and recordings aren't kept.
- **Contacts and calendar** are read on the phone, only for the tools you turn on. Ferb asks for each permission the first time a tool needs it.

## What can leave your phone

- **Gemini Nano** is downloaded and updated by Android (AICore), not by Ferb. Google's terms for AICore and ML Kit apply.
- **Ferb Link** (a separate app, optional). With it, you can have requests heard and rewritten by a model on your own computer. Their text or audio then goes to that computer over Tailscale. Ferb Link holds the internet access, not Ferb.
- **Jev** (optional, off unless you turn it on in Settings → Advanced). It picks the tool through Ferb Link. Each request's text and your tools' names then go to TypeSafe, in the US.

## Removing your data

Uninstalling Ferb removes everything it keeps. Notes in your folder stay, as they're your files.
