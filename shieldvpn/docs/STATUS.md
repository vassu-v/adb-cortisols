# Status
- [x] toolchain: rustup stable (rustc 1.100.0, user install ~/.cargo), targets aarch64/armv7/x86_64 android,
      cargo-ndk, NDK 27.3.13750724 (~/Android/Sdk/ndk), platforms android-28 + android-34, Gradle 8.9 (~/tools/gradle-8.9, on PATH via ~/.bashrc). JDK 17.
- [x] core crate (core/): adblock 0.13.6 (default-features off; embedded-domain-resolver + full-regex-handling, no single-thread), jni 0.21.1.
      5 host unit tests pass (`cargo test -j2`). Release .so (opt-z, lto, strip, panic=unwind, API 26) in app/src/main/jniLibs/<abi>/:
      arm64-v8a 2.6MB, armeabi-v7a 1.8MB, x86_64 3.0MB.
      Rebuild: cd core && ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.3.13750724 cargo ndk -P 26 -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release -j2
- [x] android app: hardening rounds 1-2 done (session object, engine loader, reply validation, EDNS clamp, TCP RST, list sanity, Updater). Builds; 18 Kotlin unit tests + 5 cargo tests pass.
- [x] security review: full review, then a regression re-review of the fixes; the final fix round (stop/start race, host exemption, error replies) was verified by tests only, not re-reviewed
- [x] memory review (findings fixed; measured ~59 MB PSS on device)
