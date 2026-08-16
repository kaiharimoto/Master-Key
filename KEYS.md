# Signing key — read this before touching anything in `keystore/`

## The one rule

**Never regenerate, replace, or delete `keystore/master-key-release.jks`.**

Android refuses to install an update over an existing app unless both APKs are
signed with the *same* key. If this key changes, the installed Master Key can no
longer be updated at all — not by the in-app updater, not by sideloading. The
only way forward would be to uninstall first, **which erases the song library and
all practice history**.

The source code can be rewritten. This key cannot be recovered.

## Why it is committed to a public repo

This is a deliberate decision, made with the tradeoff understood.

The alternative is storing the keystore in GitHub Secrets, which is strictly
safer. It was rejected because it requires manual setup in the GitHub UI for
every fresh clone or fork, and this is a single-user personal app.

**The accepted risk:** because the repo is public, anyone can build an APK that
Android will accept as a legitimate update to Master Key. To exploit that they
would have to persuade you to install their APK — and updates only ever arrive
through the in-app updater, which downloads from this repository's own releases.
The practical risk is low. It is not zero.

**If that ever stops being acceptable**, the migration is:

1. Move the four values from `keystore/signing.properties` into GitHub Secrets
   (`SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
   `SIGNING_KEY_PASSWORD`).
2. Change `app/build.gradle.kts` to read them from the environment.
3. Delete the keystore from the repo **and rewrite git history**, since the file
   stays recoverable in old commits otherwise.

Note that step 3 does not undo the exposure — the key has been public and should
be treated as such. Genuinely fixing it means rotating to a new key, which costs
you an uninstall/reinstall cycle. Use the app's **Export library** first.

## Key details

| | |
|---|---|
| File | `keystore/master-key-release.jks` |
| Format | PKCS12 |
| Algorithm | RSA 4096, SHA384withRSA |
| Alias | `masterkey` |
| Valid until | **2126** (100 years — an expired certificate is its own lockout) |
| Signature schemes | v2 + v3 (v1 disabled; `minSdk` is 26) |

v3 is enabled specifically because it is the scheme that supports **key
rotation** — the only escape hatch that exists if this key is ever compromised.

## Backup

Keep a copy of `keystore/master-key-release.jks` somewhere outside GitHub as
well — a password manager attachment or an encrypted note. Losing both the repo
and the local copy means losing in-place upgrades permanently.

## Related: sideloading in the future

Google's developer-verification programme reaches certified Android devices
globally during 2027 and will eventually gate installing unverified apps. Master
Key is unaffected today. If it ever becomes an issue, a **Limited Distribution
Account** is free, requires no government ID, and covers up to 20 devices —
which is comfortably more than this app needs.
