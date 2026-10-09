# Ganjineh — Secure Vault for Android (read-only)

Other language: [فارسی](README.fa.md).

**Read-only Android client for [Secure Vault](https://github.com/majidasgari/secure-vault)** — it pulls the
encrypted vault from any S3-compatible bucket, unlocks it on the phone with the master password, and gives
browsing, literal search and one-time codes. It never writes: the desktop stays the single writer.

## The family — three repositories

This app is one of three clients that work on **one** vault:

| repository | what it is |
| --- | --- |
| [secure-vault](https://github.com/majidasgari/secure-vault) | The vault itself: the storage format and crypto, the Qt desktop app, the web UI, the MCP bridge, two-way S3 sync and the importers. It is the only writer of the vault. |
| **[secure-vault-android](https://github.com/majidasgari/secure-vault-android)** — this repository | **Ganjineh**: pulls the vault from the S3 bucket, opens it on the phone, and offers browsing, literal search, one-time codes and per-field copy. It never writes. |
| [secure-vault-firefox](https://github.com/majidasgari/secure-vault-firefox) | The Firefox add-on: on your click it fills the user name/password/one-time code of the vault's credential entries into web login forms. Nothing is filled automatically. |

The on-disk format is the same in all three, so this app is not a "second copy" of the vault — it is a client
of the same one, and its test fixtures are built with the desktop implementation itself
(`tools/make_test_fixture.py`, `tools/make_totp_fixture.py`).

## What it does

* **S3 sync (pull only):** reads `.vault-meta.json`, `meta.sqlite`, `secure.store` and the
  `files/<aa>/<blob>.enc` blobs from the bucket prefix. Files are fetched on demand or in bulk. It never
  issues a PUT/DELETE and never touches the write lock (`.secure-vault.lock`) — the desktop stays the only
  writer (see `docs/SYNC.md` in the desktop repository).
* **Unlocking:** master password ⇒ Argon2id (the same parameters as the vault) ⇒ canary verification ⇒
  `secure.store` and every note decrypted with HKDF-SHA256 + AES-256-GCM and an AAD bound to the `blob_id`
  and the file's sensitivity level.
* **Browsing:** folders with breadcrumbs, the folder note (the map of that folder), file notes, tags, a badge
  on `secret`/`secretfile` files, and the **emoji label** of every folder/file in front of its name (the
  `emoji` column in `meta.sqlite` — the same label the desktop sets; an older mirror without that column
  still opens without an error and simply shows no label).
* **Literal search:** exact (substring) matching over the decrypted text of every note present on the device,
  with Persian normalisation: ی/ي, ک/ك, ة/ه, ا/أ/إ/آ, Arabic and Persian digits, diacritics removed, zero-width
  non-joiner and kashida. So `یخسار` is found inside `یخسارها`, and a word typed with a zero-width non-joiner matches the same
  word typed with a plain space. **The
  title of every file** is searched too (names are plaintext in `meta.sqlite`, including `secret`/`secretfile`
  ones); but the body of confidential files is never decrypted and such a hit is labelled "title only" with no
  snippet.
* **Rendering:** markdown (headings, lists, quotes, tables, code blocks with a copy button, links and images).
  Direction rule: any line containing even one Persian/Arabic letter is right-aligned, Latin text stays left,
  and code blocks are always left.
* **One-time codes and per-field copy:** an entry that has a "one-time code" field (an `otpauth://…` URI or a
  base32 seed) gets a card above the text: the grouped code for reading (`595 561`), the seconds countdown with
  a progress bar, a "copy code" button — and if the entry also has a user name/password, two more copy
  buttons. The code is generated **on this device**, from the decrypted text itself (RFC 6238, the same
  algorithm and parameters the desktop and the browser add-on use, and the same output — the tests compare
  every fixture row with the desktop implementation's own output). No network call is involved, the copied
  value is unformatted, and the clipboard entry is flagged sensitive so the system's own preview does not show
  the password on screen. A manually pasted backup code (a fixed number) is shown without a countdown, and
  `otpauth://hotp` (which needs a counter and is not stored) is not supported.
* **Confidentiality policy:** `secret`/`secretfile` entries are shown as raw text only after an explicit
  confirmation, and their **text** never appears in text search (in step with the desktop's
  `docs/SECURITY.md`). Only the *name* of these files is searchable, because names are already plaintext in
  `meta.sqlite` and visible while locked; such a result is labelled "title only" and carries no snippet.
* **Fingerprint unlock (optional):** after unlocking once with the password, the vault key can be handed to
  this phone's fingerprint sensor; from then on the login screen opens with a finger, and the password stays
  as a second route. Turning the option off (this device's settings) deletes the record and its key.
* **"Recently opened":** the home screen shows a short list of the files opened on this phone, newest first,
  with "N times" when a file was opened more than once — a shortcut to the files that are actually used. The
  first 5 rows, the rest behind "… older files". The list is stored on this device only (the app's private
  folder) and **never written to the vault**; it goes away with "clear" inside that card or with "delete local
  copy".

## What it deliberately does not do

Writing/editing/deleting, changing a sensitivity level or a tag, semantic indexing, version history, sharing,
MCP, and the access-log panel. Those are the desktop client's job.

## Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # JDK 17
export ANDROID_HOME="$HOME/Android/Sdk"               # your own SDK path

./gradlew :app:testReleaseUnitTest             # JVM tests
./gradlew :app:assembleRelease                 # signed APK
```

Signing is read from `keystore.properties` (git-ignored) or from the environment variables `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`; it is optional (without it the APK is built unsigned).

Tools:

* `tools/make_test_fixture.py` — builds a test vault **with the desktop implementation itself** (run it with
  the desktop repository's interpreter and `PYTHONPATH=<secure-vault checkout>/src`) and writes it to
  `app/src/test/resources/fixture/` (the blob format, HKDF, GCM, `secure.store` and `meta.sqlite` are the real
  format, byte for byte). The only difference: its KDF is `pbkdf2-sha512`, so a JVM test can derive the key
  without a native argon2 library.
  ```bash
  # from this repository's root, with the desktop repository's interpreter
  PYTHONPATH=/path/to/secure-vault/src /path/to/secure-vault/.venv/bin/python tools/make_test_fixture.py
  ```
* `tools/make_totp_fixture.py` — builds the one-time-code fixture with **the desktop implementation**
  (`vault.core.totp` and `credentials.parse_body`) and writes `app/src/test/resources/fixture/totp_vectors.json`:
  RFC 6238 vector rows (SHA-1/256/512), the three stored shapes (URI, bare seed, backup code), out-of-range
  parameters, and real credential bodies. The JVM tests compare every row with that same output, so if this
  client drifts from the desktop the tests turn red. The seeds in the fixture are the public RFC ones and
  well-known "test secrets".
  ```bash
  PYTHONPATH=/path/to/secure-vault/src /path/to/secure-vault/.venv/bin/python tools/make_totp_fixture.py
  ```
* A live S3 test (optional, list/get only):
  ```bash
  SVA_S3_ENDPOINT=... SVA_S3_REGION=... SVA_S3_BUCKET=... SVA_S3_PREFIX=... \
  SVA_S3_ACCESS=... SVA_S3_SECRET=... ./gradlew :app:testReleaseUnitTest --tests '*S3LiveTest*'
  ```

## First run

1. Enter the bucket coordinates (endpoint/region/bucket/prefix) and the S3 keys on the setup screen. The keys
   are sealed with the Android Keystore's hardware key (as the desktop keeps them in a `0600` file) and
   **never go into the vault**.
2. "Save and sync" fetches the vault index. After the first sync the bucket coordinates are read from
   `.vault-meta.json` itself.
3. Enter the master password. Argon2id with m=256MB takes a moment and needs that much free memory.
4. For complete search, press "fetch text notes" once (on the search screen or in settings); any note that is
   not on the device is not in the results, and the count is reported explicitly.
5. (Optional) After the first unlock with the password, "unlock with fingerprint" is offered: press "enable"
   and confirm with the sensor; from then on the login screen asks for the finger itself and there is also an
   "unlock with fingerprint" button. The same option lives in "this device's settings" (on/off). If the
   device's fingerprints change, that key is invalidated and one password entry is enough to enable it again.

## Security — honest boundaries

* Key path: password → Argon2id → the master key exists only in process memory; it is zeroed on lock.
  `store.dec` (the decrypted copy of `secure.store`) is created in `cacheDir` and deleted on lock/unlock.
* Decrypted note bodies are cached in memory only (bounded); nothing is written to disk.
* S3 keys are sealed with the Android Keystore; but like any client, code running as the same user can open
  them.
* **What this app does not protect against** (the desktop's `docs/SECURITY.md` list): a compromised running
  process, screenshots/keyloggers, and files above 10 MB, which are stored plaintext inside the vault.
* `meta.sqlite` is plaintext (names, levels, tags, log); like the desktop, this app shows only those while
  locked.
* **"Recently opened"** is a small file (`files/recent.json`) holding the *paths* of the files opened on this
  phone plus the time and the open count. It contains no decrypted text, is not written to the vault, and
  stays in the app's private folder. Its honest exposure: anyone with access to this phone learns from it
  which names you have opened — the same thing `meta.sqlite` already exposes. It goes away with "clear" in
  that card or with "delete local copy".
* **Fingerprint unlock:** the master key (32 bytes) is kept encrypted (AES-256-GCM) in `files/biometric.rec`
  inside this app, under a key in the Android Keystore that has `setUserAuthenticationRequired(true)` and
  `setInvalidatedByBiometricEnrollment(true)` — meaning that without a successful fingerprint verification the
  app itself does not hold the key. Adding or removing a fingerprint in the system settings invalidates that
  key and drops the record too (one password entry, then enable it again). The option is off by default;
  turning it off deletes both the record and the key, and so does "delete local copy". Its honest cost: on
  this phone the password is no longer the only way in — any finger the sensor accepts opens the vault while
  the record is there. The record is also bound to one vault: the vault id is checked and the key must open
  the canary, otherwise the record is discarded.

## Known limitations

* The `pbkdf2-sha512` KDF path is tested on the JVM; the `argon2id` path must be confirmed once on a device
  against the canary (if the key is one bit off, unlocking fails with "wrong password" — which is itself the
  test).
* Non-ASCII passwords on the PBKDF2 path may differ between Python and the JVM (password encoding in
  `PBEKeySpec`). argon2id vaults (including your own) do not use that path.
* The one-time-code card and the copy buttons do not render on this machine (no emulator/system image) and
  must be confirmed by eye on the phone once; their logic (code generation, body parsing, grouping, non-OTP
  shapes) is covered by JVM tests.
* The UI cannot run on this machine (no emulator). Version 1.2.0 was installed and launched on the phone: the
  login screen renders with all its labels and values, the system `BiometricPrompt` comes up on the login
  screen (`uiautomator dump`), the app runs without a crash, and a content search over 226 fetched notes
  completed on that device (no crash); but the sensor path and the graphical look of the screens are
  confirmed by the user on the phone.

