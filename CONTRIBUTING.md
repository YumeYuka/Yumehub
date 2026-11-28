# Contributing

## Setup

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/YumeYuka/Yumehub.git
```

For an existing checkout, run `git submodule update --init --recursive`.
See the [README](README.md) for an overview of the project.

## Build and validation

Use the Gradle wrapper. Run targeted tests and builds for your changes:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

On Linux or macOS, use `bash ./gradlew` instead. Run `ktfmtCheck` for Kotlin
formatting. Report the commands run and any checks not completed.

Each branch push builds a debug APK. Download `apk-debug` from the workflow
run's **Artifacts** section in [GitHub Actions](https://github.com/YumeYuka/Yumehub/actions).

## Localization

Use the shared string tables for UI text, including accessibility labels. When adding a key to an existing domain, update its English and Simplified Chinese tables in the domain's `*Strings.kt` file. Wire new domains into `AppStrings.kt`, `EnStrings.kt`, and `ZhHansStrings.kt`; update `Strings.kt` when locale selection or fallback changes.

## Pull requests

Keep each change focused. Describe its behavior, scope, and validation in the
pull request. Do not include credentials or local configuration files.

Contributions are distributed under [AGPLv3](LICENSE).
