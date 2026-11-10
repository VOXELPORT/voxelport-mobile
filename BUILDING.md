# Building VoxelPort for Android

Two files are too large or too separate to live in this repository, and `.gitignore` leaves them out.
You supply them once, then it is a normal Gradle build.

| File                                     | What it is                                         |
| ---------------------------------------- | -------------------------------------------------- |
| `app/libs/vpmobile.aar`                  | The VoxelPort tunnel, written in Go (about 4.5 MB) |
| `app/src/main/assets/jre25-arm64.tar.xz` | A Java 25 runtime for Android arm64 (about 38 MB)  |

## What you need

- **JDK 17 or newer** to run Gradle (the official build uses JDK 21).
- **Android SDK** with platform 35, build-tools 35.0.0 and **NDK 27.2.12479018** (the app has a small C
  launcher, built with CMake).
- **Go 1.24 or newer** and `gomobile`, only to build the tunnel library.
- A phone, or an emulator image, running Android 8.0+ on **arm64**. The app ships arm64 libraries only
  (the bundled Java is arm64), so it will not install on x86 images.

## 1. The tunnel library (`vpmobile.aar`)

The tunnel lives in VoxelPort's Go `cli` project, package `mobile`. It is the same code the command-line client
uses. **That repository is not public yet**, so the commands below only work for someone who has access to it; if you
do not, you cannot build `vpmobile.aar` yourself for now. Ask in the [Discord](https://discord.gg/JAUfmey2fP) if you
want to build the app.

```sh
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

git clone https://github.com/VOXELPORT/cli
cd cli
# ANDROID_HOME must point at your Android SDK (with the NDK installed)
gomobile bind -target=android/arm64 -androidapi 26 -javapkg com.voxelport.go -o vpmobile.aar ./mobile
```

Copy the result to `app/libs/vpmobile.aar`. Keep `-javapkg com.voxelport.go`: the app imports the
classes as `com.voxelport.go.mobile.Mobile`. If `gomobile` asks you to run `go get golang.org/x/mobile/bind`,
do that and run the command again.

## 2. The Java runtime (`jre25-arm64.tar.xz`)

The app unpacks this archive into its private storage and starts it as the server's JVM
(`Setup.installJre`). It must be an **OpenJDK 25 runtime built for Android arm64** (bionic libc), packed as
a `.tar.xz` with the runtime's files at the top level, not inside a folder:

```
bin/  conf/  legal/  lib/  release        (lib/libjli.so and release must exist)
```

VoxelPort uses a build from the Android OpenJDK build project behind Amethyst and PojavLauncher
([AngelAuraMC/angelauramc-openjdk-build](https://github.com/AngelAuraMC/angelauramc-openjdk-build), a fork
of [PojavLauncherTeam/android-openjdk-build-multiarch](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)).
Take the Java 25 arm64 runtime from their releases, or build one with those scripts, and save it as
`app/src/main/assets/jre25-arm64.tar.xz`. It stays compressed in the APK (`noCompress += "xz"`), so the app
can stream it. OpenJDK is GPLv2 with the Classpath Exception.

## 3. Build the app

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # app/build/outputs/apk/release/
./gradlew testReleaseUnitTest  # the JUnit tests (tuning, YAML edits, data meter, update checks)
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Signing a release

Without any setup, `assembleRelease` signs with your **debug key**, so a fresh checkout still builds. An APK
signed with a different key cannot update an installed copy that was signed with another one: yours and the
official VoxelPort build are separate apps as far as Android is concerned.

To sign with your own key, create `keystore.properties` next to `settings.gradle.kts`:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`keystore.properties`, `*.jks` and `*.keystore` are in `.gitignore`. **Never commit them**, and back the
key up somewhere safe: losing it means your installed users can never update.

## Private dev build (testing on your own phone)

`assembleDev` makes **VoxelPort Dev**: another package (`com.voxelport.android.dev`, so it installs next to the real app and never touches it),
signed with the release key, with a version that rises with every build. It updates itself from a **private feed**: a file on voxelport.in
with a secret name, `/dl/dev-<devToken>.json`. The real app never reads that feed, and the dev app never reads the public one.

1. Put `devToken=<random hex, 16+ characters>` in `dev.properties` (gitignored). Anyone with the token can fetch the dev build.
2. Run `bash tools/publish-dev.sh "what changed"`. It builds the dev app, uploads the APK and the feed over ssh, and prints the install link.
3. On the phone, open the install link once. After that the dev app offers each newer dev build itself (it checks when it opens, at most every 6 hours; the footer button checks at once).

The dev app has its own identity, so sign in to Discord once in it. Do not run its server and the real app's server at the same time (both use port 25565 on the phone).

## Notes for maintainers

- The visible version is `versionName` in `app/build.gradle.kts`; raise `versionCode` for every build you
  publish. The in-app updater compares `versionCode` only.
- Since build 19 the app reads the update feed from BOTH https://voxelport.github.io/dl/android.json (the main website) and
  https://voxelport.in/dl/android.json, takes the newer of the two, and downloads the APK from whichever site the feed came from. Earlier
  builds only know voxelport.in, so a release is always published there first and then mirrored to GitHub Pages
  (`deploy/publish-android.sh` in the website project reads the voxelport.in feed, checks size and SHA-256, and copies both files).
- Building on Windows works too: JDK 21 (Gradle runs on it), the Android SDK with the packages above, and for `vpmobile.aar`
  `gomobile bind` run on a COPY of the cli project (it edits go.mod). The Java runtime asset can be taken from the last released APK
  (`assets/jre25-arm64.tar.xz`). Put `sdk.dir=C:/path/to/sdk` in `local.properties` (forward slashes).
- `tools/publish-android.sh` is how the official build is published to voxelport.in. It reads the version
  from the APK itself, refuses to publish an older build over a newer one and writes the update manifest
  the app checks. It runs on VoxelPort's own server and is here for reference.
