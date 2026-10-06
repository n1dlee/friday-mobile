# Signing key

`friday.keystore` signs the owner's Friday builds, debug and release. It is
**not in the repository** (see `.gitignore`); builds made without it are signed
with the Android SDK's own debug key instead, which works for trying Friday out
but cannot update an install signed with the owner's key.

**Back this folder up.** It is the identity of the app:

- Android only installs an update over an existing Friday if it is signed with
  the same key. A build signed with any other key has to replace the app, and
  replacing it wipes its data — memory, voice profile, settings, chat history.
- Gmail access is registered in Google Cloud against this key's SHA-1
  (`4B:B3:CB:54:33:D6:E0:EC:B8:4B:4F:AE:F8:15:40:D7:59:F9:82:96`).

It is a copy of the Android SDK's standard debug key from the machine Friday was
first built on, so it carries that key's well-known passwords (`android`). Fine
for an app installed only on its owner's phone; not for anything published.
