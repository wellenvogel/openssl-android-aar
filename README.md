# openssl-android-aar

Builds **OpenSSL 3.x** as an Android **Prefab AAR** using NDK 29.

The entire build — downloading OpenSSL source, locating or auto-installing
the NDK, cross-compiling for all ABIs, and packaging the AAR — is driven by
a single Gradle invocation. No shell scripts, no manual steps.

| Attribute       | Value                                      |
|-----------------|--------------------------------------------|
| NDK version     | **29** (`29.0.13113456`)                   |
| compileSdk      | **35**                                     |
| minSdk          | **21**                                     |
| OpenSSL         | **3.3.2** (static, `no-shared`)            |
| ABIs            | `arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86` |
| 16 KB page-size | `-Wl,-z,max-page-size=16384`               |
| CMake targets   | `openssl::crypto`, `openssl::ssl`          |

---

## Prerequisites

| Tool        | Notes                                             |
|-------------|---------------------------------------------------|
| JDK 17+     | Required by Gradle 8.9                            |
| Perl 5.10+  | Required by OpenSSL's `./Configure` script        |
| `make`      | Standard build tool                               |
| Android SDK | With `sdkmanager` available (for NDK auto-install)|

The NDK is located automatically in this order:
1. `$ANDROID_NDK_HOME` or `$ANDROID_NDK_ROOT` environment variables
2. `$ANDROID_HOME/ndk/29.0.13113456` (standard SDK layout)
3. Auto-installed via `sdkmanager "ndk;29.0.13113456"` if sdkmanager is found

---

## Build

### Step 1 — Install the Gradle wrapper jar (one-time)

```bash
bash scripts/install_gradle_wrapper.sh
```

### Step 2 — Build the AAR

```bash
./gradlew :openssl:assembleRelease
```

This single command will:
1. Download `openssl-3.3.2.tar.gz` and verify its SHA-256
2. Locate or auto-install NDK 29
3. Cross-compile OpenSSL for all four ABIs
4. Assemble the Prefab AAR

Output:
```
openssl/build/outputs/aar/openssl-release.aar
```

Individual tasks can also be run independently:
```bash
./gradlew :openssl:downloadOpenSSL   # just download & verify the tarball
./gradlew :openssl:buildOpenSSL      # download + compile all ABIs
./gradlew :openssl:assemblePrefab    # build + assemble prefab/ tree
./gradlew :openssl:packageAar        # full AAR (same as assembleRelease)
```

### Publish to local Maven

```bash
./gradlew :openssl:publishReleasePublicationToLocalRepository
# Repository: openssl/build/repository/
```

---

## AAR internal layout

```
openssl-release.aar
├── AndroidManifest.xml
├── classes.jar                          ← empty stub (AAR spec requirement)
└── prefab/
    ├── prefab.json
    └── modules/
        ├── crypto/
        │   ├── module.json
        │   ├── include/openssl/*.h
        │   └── libs/
        │       ├── android.arm64-v8a/  abi.json  libcrypto.a
        │       ├── android.x86_64/     …
        │       ├── android.armeabi-v7a/…
        │       └── android.x86/        …
        └── ssl/
            ├── module.json             ← export_libraries: [":crypto"]
            ├── include/openssl/*.h
            └── libs/  (same structure, libssl.a)
```

---

## Consuming the AAR

**`build.gradle.kts`** of the consuming module:
```kotlin
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("com.android.ndk.thirdparty:openssl:3.3.2-1@aar")
}
```

**`CMakeLists.txt`**:
```cmake
find_package(openssl REQUIRED CONFIG)
target_link_libraries(mylib openssl::ssl)   # ssl pulls in crypto automatically
```

---

## Incremental builds

All tasks are fully incremental:
- `downloadOpenSSL` is skipped if the tarball exists and its SHA-256 matches
- `buildOpenSSL` is skipped per-ABI if the `.a` files already exist
- `assemblePrefab` and `packageAar` respect Gradle's up-to-date checks

Delete `openssl/build/` to force a full rebuild.
