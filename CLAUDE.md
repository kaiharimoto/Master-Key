# Working on Master Key

Master Key is a single-user personal app. It has one user, who is also the only
reviewer, and it is not open to the public. The workflow below follows from
that — do not carry it over to a repo with other contributors.

## Ship straight to main

**Commit and push to `main`. Do not open pull requests or feature branches.**
There is nobody waiting to review them, and a branch sitting unmerged only means
the change cannot be tested on the tablet.

**Then cut a release, in the same session, without being asked again.** A change
that is not installable has not been delivered: the whole point of a push is to
get an APK the user can put on the tablet. Finishing a task means main is pushed
*and* a release is published.

```bash
git push -u origin main
git tag v1.1.0 && git push origin v1.1.0   # publishes a real release
```

A tag push builds a signed APK and publishes it as a real GitHub release, which
is what the in-app updater looks for.

**If the tag push is rejected with a 403** — Claude Code's git proxy allows
branch pushes but not tag pushes — dispatch the **Release** workflow instead. It
creates the tag itself, so it is a complete release path on its own:

```
run_workflow release.yml, ref=main, inputs={version: "1.1.0", prerelease: "false"}
```

`prerelease` **must** be set to false. It defaults to true, and
`/releases/latest` ignores prereleases, so the installed app would never be
offered the build. Only leave it true when deliberately exercising the pipeline
rather than shipping.

Wait for the release workflow to finish, confirm `/releases/latest` is the new
version with the APK attached, and report the release URL. If it fails, fix it
and cut the next version — never retag.

## Versions

Versions come from the tag, nowhere else. `v1.2.3` → versionCode `10203`, via a
formula written out in three places that must agree: `app/build.gradle.kts`,
`.github/workflows/release.yml`, and `UpdateRepository.kt`.

**Versions are immutable.** Republishing an existing version is refused by the
workflow, because overwriting a release breaks the updater for anyone already
running it. Always go forward: bug fixes bump the patch, anything the user will
notice bumps the minor.

Check what is already out before picking a number — the latest release is the
one the tablet is running.

## Before you push

Everything CI runs, run first. Pushing straight to main means there is no
pre-merge gate, so the local run *is* the gate.

```bash
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleRelease

# Three suites that cannot be reached from a JVM test:
c++ -std=c++17 -Wall -Wextra -o /tmp/eqt audio/src/main/cpp/test/event_queue_test.cpp && /tmp/eqt
npm --prefix tools/score-test ci
node tools/score-test/scorepane.test.mjs   # DOM and timemap, under jsdom
node tools/score-test/render.test.mjs      # actual pixels, under headless Chromium
```

The release workflow re-runs the JVM tests and the signed build before it
publishes, so a broken commit fails the release rather than shipping — but it
fails *after* the push, which is a slower way to find out.

If the Android SDK is missing (a fresh container, say), installing it is usually
worth it rather than guessing: `sdkmanager` needs `platforms;android-37.0` from
the beta channel (`--channel=3`), `build-tools;37.0.0`, `ndk;28.2.13676358` and
`cmake;3.22.1`, plus `sdk.dir` in `local.properties`.

## Things that will bite

**Never touch `keystore/`.** Read [KEYS.md](KEYS.md) before going near it. If the
signing key changes, the installed app can no longer be updated at all, and
recovering means an uninstall that erases the song library.

**Room schema changes need a version bump and a committed schema JSON.** CI fails
if `app/schemas` is stale. This is what broke v1.0.1 — a column added without a
database version bump, crashing at startup on every launch, with the in-app
updater unreachable behind the crash.

**The audio callback cannot allocate, lock, or block.** Anything reaching the
synth goes through the lock-free ring in `audio/src/main/cpp/event_queue.h`. Note
that the ring delivers events in production order, which is not musical order —
the callback sorts them through an `EventTimeline` before applying them. Skipping
that step makes chords play a single note.

**The score pane fails invisibly, and jsdom cannot see it.** `score.js` catches
everything and replaces itself with a polite message, so a total failure looks
exactly like a feature that was never built. Worse, the pane can engrave
perfectly and still show nothing readable — too small, scrolled out of view, or
dimmed into the background — and the jsdom suite passes throughout, because
jsdom has no layout and paints no pixels. Two releases shipped blank that way.
**After touching anything in `app/src/main/assets/score/`, run `render.test.mjs`
too, and look at the screenshot** (`--save out.png`); it is the only check that
sees what the tablet sees.
