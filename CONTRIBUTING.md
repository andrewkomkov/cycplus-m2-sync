# Contributing

## Reporting a device

The single most useful contribution is telling us whether your bike computer works.
Open a [device report](https://github.com/andrewkomkov/cycplus-m2-sync/issues/new?template=device_report.yml)
and attach the output of:

```bash
python tools/m2_cli.py info
python tools/m2_cli.py services
```

That dump is enough to tell whether a model needs a different index file
(`workouts.json` instead of `filelist.txt`), a different idle reply, or nothing at all.

## Code

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests are plain JVM ones: the calorie and scale maths as unit tests, the screen as
Compose UI tests on Robolectric — no emulator, no connected phone, and CI runs the same
task. A change to the screen belongs with a test in
`android/app/src/test/java/dev/komkov/m2sync/UiTest.kt`.

Anything that touches the protocol or the import has to be checked against a real
device — please say which model and firmware in the pull request, and paste the
relevant part of `adb logcat -s M2SYNC`. After an import, `VERIFY` reads the data
back out of Health Connect; the distances it prints must match the `.fit`.

Commit messages and pull request titles follow
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) — release-please
derives the version and the changelog from them, so `feat:` and `fix:` are not cosmetic.

Strings live in `android/app/src/main/res/values/strings.xml` (English, the default) and
`values-ru/strings.xml`. New user-visible text goes into both; the log speaks the system
language too.
