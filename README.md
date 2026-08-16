# Master Key

A piano-learning app for Android. Plays a MIDI file as a falling-note highway
descending onto a drawn keyboard, optionally alongside the real engraved score,
and is built for someone who is learning to read music rather than someone who
already can.

## What it does

**Falling-note highway.** Notes descend to a key line where a piano keyboard is
drawn. Note height is duration, so held notes are visibly long. Right hand is
amber, left hand is teal, and that colour means the same thing everywhere — on
the falling note, on the notehead in the score, and on the key it lands on.

**It picks the keyboard range for you.** Drawing all 88 keys wastes most of the
screen on a piece that uses two octaves, and makes the keys too narrow to line up
with the notes above them. Master Key analyses the score, snaps the range to
white keys (black keys straddle the seam between two whites, so a black endpoint
would leave a half key hanging off the edge), widens tiny exercises to a usable
minimum, and pans smoothly between sections when a piece is genuinely too wide
for one window — only ever at a rest, never mid-phrase.

**Sheet music, in sync.** If you supply the MusicXML alongside the MIDI, the real
engraved score appears above the highway with the current bar highlighted and the
sounding noteheads lit in the hand colours. The whole measure is highlighted
rather than a thin cursor line, because losing your place happens at the bar
level, not the note level.

**Reading aids that are designed to be switched off.** Note names, fingering,
landmark notes, hand colouring and the keyboard mini-map are grouped under one
dial that runs Max → Guided → Minimal → Off. The research is consistent that
annotated notation helps beginners, but only when it is an explicit transitional
scaffold — left on permanently it becomes a crutch. So it is arranged as a
visible path towards reading unaided, and note names can fade as you improve.

**Practice tools.** Tempo from 25% to 125% with no pitch change, loop any bar or
range, hands separate, count-in and metronome, and a tempo drill that alternates
fast and slow rather than ramping monotonically upward — which is what the
evidence actually supports.

## Installing

Download the APK from the [latest release][releases] and open it on your tablet.
Android will ask you to allow installing apps from your browser or file manager;
that grant is per-app and only needed once.

**After the first install you never need to do this again.** The app checks
GitHub on launch and offers updates in place.

[releases]: https://github.com/kaiharimoto/Master-Key/releases/latest

## Adding songs

Tap **Add song** and pick your `.mid`. If you select its `.musicxml` (or `.mxl`)
at the same time, they are paired automatically by filename. You can also attach
sheet music to an existing song later from the library.

Files are copied into the app rather than referenced in place, so moving or
deleting the originals won't break your library.

## How it is built

| | |
|---|---|
| UI | Kotlin, Jetpack Compose (Material 3) |
| Audio | TinySoundFont rendered through Oboe, driven by a custom event scheduler |
| MIDI | [ktmidi](https://github.com/atsushieno/ktmidi) |
| Notation | [Verovio](https://www.verovio.org/) 6.2 (WASM) in a local WebView |
| Piano | [FreePats Upright Piano KW](https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html) (CC0) |

Three design decisions carry most of the weight:

**The audio callback is the clock.** Position is `framesRendered / sampleRate`,
read by everything else. A UI-side timer would gradually drift against the audio
and the falling notes would stop matching what you hear.

**Nothing calls the synth from the UI thread.** Note events go into a lock-free
ring buffer that the audio callback drains itself, applying them at exact frame
offsets. That makes note timing sample-accurate rather than buffer-accurate, and
it is why a run of sixteenths doesn't sound lumpy.

**Tempo change is free.** Because playback synthesises from note events rather
than replaying audio, playing at half speed just means spacing the same events
twice as far apart. No time-stretching, so no pitch change and no artefacts.
Per-hand muting and seamless looping fall out of the same design.

### Building it yourself

```bash
./gradlew :app:assembleRelease   # signed APK in app/build/outputs/apk/release/
./gradlew :core:test             # unit tests
```

Requires JDK 21 and the Android SDK with NDK 28.2.13676358 and CMake. The release
signing key is committed deliberately — see [KEYS.md](KEYS.md), which also
explains why it must never be regenerated.

### Releasing

```bash
git tag v1.0.1 && git push --tags
```

CI builds, verifies the signature and 16 KB page alignment, and publishes the APK
to a GitHub release. The app picks it up on next launch.

You can also run the **Release** workflow manually from the Actions tab, which
does the same thing and creates the tag for you. It publishes a prerelease by
default — `/releases/latest` ignores those, so you can exercise the pipeline
without the installed app treating it as an update; untick the box to cut a real
release.

Versions are immutable: republishing an existing version is refused, because
overwriting a release would break the updater for anyone already on it.

## Licences

Master Key bundles Verovio (LGPL-3.0) as a separate, replaceable asset,
TinySoundFont (MIT), Oboe (Apache-2.0), ktmidi (MIT), and the FreePats Upright
Piano KW sound bank (CC0). Full texts ship in the app under Settings.
