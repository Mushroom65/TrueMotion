<p align="center"><img src="branding/icon.png" alt="True Motion" width="128"></p>

# True Motion Next

An experimental Android motion graphics editor based on [AbsentMotion 4.4.0](https://github.com/Comett1225/AbsentMotion/tree/v4.4.0), with tools being ported from the earlier True Motion mod.

[Русская инструкция](README_TRUE_MOTION_RU.md) · [Port status](docs/TRUE_MOTION_MIGRATION_RU.md) · [Telegram](https://t.me/TrueMoti0n)

0.17.1 fixes disappearing shared-depth layers in Graphite, preserves artwork keys when binding/rebinding/deleting bones, corrects bone viewport orientation, and integrates repetition and value links into existing property/effect inspectors. The separate Motion graphics page is removed. [Fixes and usage](docs/RELEASE_0_17_1_RU.md).

0.17.0 adds opt-in shared per-fragment depth for planes, models and scene particles, a scene light, per-copy offsets and persistent value links. Rig branches can start at any point on a bone; preview signing now uses a pinned certificate. This is a partial roadmap implementation, with Android device validation pending. [Usage and limits](docs/RELEASE_0_17_RU.md).

0.16.0 moves the media library into layer properties, with thumbnails, gallery import and explicit replacement keys. Rigging now runs in the viewport with direct bone drawing and automatic artwork binding; bones stay out of the layer stack. Rigs use the existing My resources workflow. [Usage and limits](docs/RELEASE_0_16_RU.md).

0.15.0 adds a main-editor project library with held media replacement keys, cutout bone rigs with FK/two-bone IK, and a separate saved-model tab. Experimental preview; Android device validation is pending. [Usage and limits](docs/RELEASE_0_15_RU.md).

0.14.1 fixes native color grading being replaced by its Classic counterpart on project open and recovers identifiable affected instances. [Hotfix notes](docs/RELEASE_0_14_1_RU.md).

## Included in 0.14.0

- Upstream stable v4.4.0 merged, including its OpenGL backend and new editor tools.
- `.alight` / XML import directly from Projects and `.alight` / `.absproj` package export.
- Full effect color picker, multiple media selection and direct reopening of vector animation layers.
- Native XYZ camera rotation and an optional viewport-only camera frame matte.
- 575 freshly adapted AfterMotionZ+ effects in 11 separate categories, including Deep Glow AE and Saber AE; 575 donor definitions preserved across the update; 225 core/tool definitions. [Scope and validation](docs/AFTERZ_EFFECTS_RU.md).
- 1,746 additional legacy shader/script definitions in separate True Motion Classic categories, plus the existing native mappings. [Coverage](docs/LEGACY_EFFECTS_014_RU.md).
- Audio spectrum: stereo FFT, seven styles, audio/video source selection, cached analysis and keyframes.
- Parent anchor edits and subsequent parent rotation/scale share one affine transform.
- Native color grading with wheels, curves and HSL controls.
- Live audio-driven animation referencing an audio layer, beat markers and Bop.
- Native particle simulation with 2D/3D generation, scene-camera projection, lifetime controls and layer textures.
- Editable nested groups, anchor controls and spatial Bézier motion paths; imported Alight pivots and parent transforms retain their original convention.
- Video frame/composition to image layer, playback up to 100× with audio.
- JavaScript plugin commands, hooks and bundled effects; separate vector animation editor.
- Manual viewport prerender, green timeline cache indicators and a 2 GiB disk cache.
- Replace a media layer from Fill & Color, paused audio-spectrum preparation and lossless transparent preview caching. [0.14.0 notes](docs/RELEASE_0_14_RU.md).
- Animated GIF import and the supplied True Motion launcher/in-app logo.

The port is **not complete**. The remaining donor effects, advanced rigging tools, automatic caching of every viewed frame and the new Windows renderer remain incomplete. Consult the [feature status](docs/TRUE_MOTION_MIGRATION.json) before relying on a feature. Unknown legacy effects are preserved on import, but exact rendering compatibility is not guaranteed.

## Build the compatible Android app

Requirements: JDK 21, Android SDK platform `android-37.0`, build-tools `37.0.0` and the checked-in Gradle wrapper. This compatible build is intended for Android 9+ on ARM64; its declared minSdk follows upstream (26). Older devices have not been tested. Set `ANDROID_HOME` or create your own untracked `local.properties`.

```sh
git clone https://github.com/Mushroom65/TrueMotion.git
cd TrueMotion
./gradlew -PtrueMotionNativePrebuilt=true :app:assembleCompatDebug
```

On Windows use `gradlew.bat`. The result is `app/build/outputs/apk/compat/debug/app-compat-debug.apk`. The package is `app.truemotion`, separate from the earlier APK mod. Locally generated debug signatures differ between machines; they cannot update an APK signed on another machine without its original key. No signing keys are included here.

The fast build uses the included native libraries. The renderer core was rebuilt from the combined C++ source; the other libraries match the official compatible upstream APK. Exact hashes and source provenance are recorded in [TRUE_MOTION_PROVENANCE.json](docs/TRUE_MOTION_PROVENANCE.json). Native changes require rebuilding the core: see [native build notes](docs/NATIVE_AUDIO_BUILD_RU.md) and [upstream build documentation](docs/BUILD.md). For a full native build: `git submodule update --init --recursive`.

GitHub Actions checks the compatible debug build and uploads JVM test reports. Installable previews are published only by the workflow that verifies the permanent signing certificate. The separate manual **Publish True Motion preview** workflow accepts a full source commit SHA and its matching version tag, reruns validation, and publishes a preview release with the APK, source archives, checksums and rig example. It refuses to replace an already published release. From 0.17.0, release APKs must use the pinned permanent preview certificate. CI accepts the signing secret or verifies a prepared signed APK against a clean source build. See the 0.17.0 notes for backup and setup.

## Artwork and validation

0.14.0: 1,695 JVM tests, 0 failures, 30 environment skips. Android smoke sweeps compiled and rendered all 1,746 Classic effects in Vulkan and OpenGL, and reran the 575 AfterMotionZ+ effects. Media replacement, paused spectrum, transparent cached playback, parent anchors, Project 122 and particle exports were checked. The reported OnePlus crash remains unverified on a physical device. [Validation and limits](docs/RELEASE_0_14_RU.md).

0.12.0: 1,610 JVM tests passed locally (30 environment skips). Android native checks cover all 575 donor effects, the existing tools, editable `.alight` roundtrips and XYZ camera projection in Vulkan/OpenGL. Import/export and the new controls were reviewed through the app UI. [Validation details](docs/RELEASE_0_12_RU.md).

The exact user-provided logo is retained in `branding/true-motion-source.png`. With Pillow installed, run `python tools/icon/package_true_motion.py` to reproduce the app and adaptive/themed launcher assets.

The 0.10.0 compatible build passed [GitHub Actions](https://github.com/Mushroom65/TrueMotion/actions/runs/41078996060): 1,252 JVM tests, 0 failures, 8 skips, plus source/native hash checks. The local APK was installed and its branding checked in MuMu. Native instrumentation with export pixel comparisons belongs to the 0.9.0 feature validation. A physical phone was not tested; see [release validation notes](docs/RELEASE_0_10_RU.md).

The 0.11.0 local validation passed 1,257 JVM tests (0 failures, 12 environment skips), compiled and rendered all 575 new effects on Android, and reran the existing native feature checks. Deep Glow PNG/export matched preview; decoded MP4 mean error was 0.9001/255. Donor-wide visual parity and physical-device validation remain unverified.

## Credits and licences

AbsentMotion authors: source base and renderer, Apache-2.0. Original [LICENSE](LICENSE), [NOTICE](NOTICE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) are retained. Rhino uses MPL-2.0; its notices are in `app/src/main/assets/true-licenses`. Earlier mod credits remain **Toji Motion / Alight Motion**. The archived upstream README is in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

Effect shaders and available thumbnails were adapted from the user-supplied AfterMotionZ+ Clone APK. Donor provenance, author references and unsupported definitions are recorded in [AFTERZ_EFFECT_PORT.json](docs/AFTERZ_EFFECT_PORT.json) and [port notes](docs/AFTERZ_EFFECTS_RU.md). The donor APK, decryptor DEX and decryptor native library are not distributed here.
