# Contributing Guidelines

You can contribute to km-io by reporting issues or submitting changes via pull request.

## Reporting issues

Please use [GitHub issues](https://github.com/KotlinMania/km-io/issues) for filing feature
requests and bug reports.

Questions about usage and general inquiries are also welcome on the issue tracker, or in
[GitHub Discussions](https://github.com/KotlinMania/km-io/discussions) on this repository.

## Submitting changes

Submit pull requests at [github.com/KotlinMania/km-io/pulls](https://github.com/KotlinMania/km-io/pulls).
Please keep in mind that maintainers will have to support the resulting code of the project,
so do familiarize yourself with the following guidelines.

* Base your PRs against the `main` branch. There is no separate long-lived `develop`
  branch; short-lived feature branches are merged into `main` once review and CI pass.
* Documentation updates in markdown files can land directly against `main` when no code
  changes are involved.
* If you make any code changes:
    * Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).
    * Use 4 spaces for indentation.
    * Use star imports (`import foo.bar.*`) where the file already uses several symbols
      from the same package, otherwise keep imports explicit.
    * [Build the project](#building) to make sure everything compiles and the tests pass.
* If you fix a bug:
    * Write a test that reproduces the bug first.
    * Fixes without tests are accepted only in exceptional circumstances where writing the
      corresponding test is genuinely impractical (for example, race conditions that cannot
      be reproduced deterministically).
    * Follow the style of writing tests used in this project: name test functions as
      `testXxx`. Don't use backticks in test names — Kotlin allows backticked function
      names but they don't render well across every target's test report.
* Comment on the existing issue if you want to work on it. Ensure that the issue not only
  describes a problem but also describes a solution that has received positive feedback.
  Propose a solution if none has been suggested.

## Building

km-io is built with Gradle. The Gradle wrapper is checked in, so you don't need a system
Gradle installation.

* Run `./gradlew build` to compile every configured target and run the host-portable
  tests. On a clean checkout the first invocation also installs a project-local Android
  SDK under `.android-sdk/` so the Android targets can be built without an external SDK.
* Run `./gradlew check` to run the test suite without rebuilding artifacts you don't
  need.
* Run `./gradlew jvmTest` to run only the fast JVM tests during development.
* Run `./gradlew <target>Test` for a specific target's tests, e.g. `jvmTest`,
  `jsNodeTest`, `wasmJsNodeTest`, `wasmWasiNodeTest`, `linuxX64Test`,
  `mingwX64Test`, `macosArm64Test`, or any of the iOS / tvOS / watchOS / Android Native
  simulator-test tasks.
* Run `./gradlew :testReleaseUnitTest` for the Android KMP library host-side unit tests.

Host-portable tests (JVM, JS Node, Wasm-JS Node, Wasm-WASI Node, macOS arm64, Android
host unit tests) run on every contributor's machine. Targets that need a different host
or simulator (iOS device, mingwX64 on Windows, Linux native on Linux) are validated on CI.

You can import this project into IntelliJ IDEA. Delegate build actions to Gradle
(in **Preferences → Build, Execution, Deployment → Build Tools → Gradle → Build and run**).

### Public API stability

km-io is in the `0.1.x` series and is marked as
[alpha](https://kotlinlang.org/docs/components-stability.html) — APIs may change between
minor versions. We still try to avoid gratuitous breakage and to call out incompatible
changes in [`CHANGELOG.md`](CHANGELOG.md).

API surfaces compiled into the published artifact can be inspected via Kotlin's binary
compatibility validator. When the public API legitimately changes, regenerate the API
dump and commit it alongside the source change:

```
./gradlew updateLegacyAbi
```

## Reviewing

We try to give every PR a first response within a week. Larger architectural changes
benefit from being floated as an issue first so that maintainers and contributors can
agree on shape before code review starts.

## License

By contributing, you agree that your contributions will be licensed under the same
[Apache 2.0 License](LICENSE) as the rest of km-io.
