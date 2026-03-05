# openssl-android-aar

Builds **OpenSSL 3.5.5 LTS** as an Android **Prefab AAR** using NDK 29.

The entire build — downloading the OpenSSL source from GitHub, verifying its
SHA-256, locating or auto-installing the NDK, cross-compiling for all ABIs,
and packaging the Prefab AAR — is driven by a single Gradle invocation. No
shell scripts, no manual steps.

| Attribute       | Value                                        |
|-----------------|----------------------------------------------|
| Group           | `de.wellenvogel.android.ndk.thirdparty`      |
| NDK version     | **29** (`29.0.13113456`)                     |
| minSdk          | **21**                                       |
| OpenSSL         | **3.5.5 LTS** (supported until April 2030)   |
| ABIs            | `arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86` |
| 16 KB page-size | `-Wl,-z,max-page-size=16384`                 |
| CMake targets   | `openssl::crypto`, `openssl::ssl`            |
| Repository      | Flat local Ivy                               |

OpenSSL 3.5.5 is the first LTS release with native 16 KB ELF page-size support
(PR #28277, backported from 3.6). Supported until April 2030.

---

## Prerequisites

| Tool        | Notes                                              |
|-------------|----------------------------------------------------|
| JDK 17+     | Required by Gradle 8.9                             |
| Perl 5.10+  | Required by OpenSSL's `./Configure` script         |
| `make`      | Standard build tool                                |
| Android SDK | With `sdkmanager` available (for NDK auto-install) |

The NDK is located automatically in this order:
1. `$ANDROID_NDK_HOME` or `$ANDROID_NDK_ROOT` environment variables
2. `$ANDROID_HOME/ndk/29.x` (standard SDK layout, exact or latest 29.x)
3. Auto-installed via `sdkmanager "ndk;29.0.13113456"` if sdkmanager is found

---

## Build

### Compile

```bash
# Static libraries (default) — produces openssl-release.aar
./gradlew assembleRelease

# Shared libraries — produces openssl-shared-release.aar
./gradlew assembleRelease -PbuildShared=true
```

This single command will:
1. Download `openssl-3.5.5.tar.gz` from GitHub and verify its SHA-256
   (checksum fetched dynamically from the same GitHub release)
2. Locate or auto-install NDK 29
3. Cross-compile OpenSSL for all four ABIs
4. Assemble the Prefab AAR

Output:
```
build/outputs/aar/openssl-release.aar
build/outputs/aar/openssl-shared-release.aar
```

### Publish to local Ivy repository

```bash
# Static
./gradlew publishReleasePublicationToLocalRepository

# Shared
./gradlew publishReleasePublicationToLocalRepository -PbuildShared=true
```

Repository layout (`build/repository/`):
```
de.wellenvogel.android.ndk.thirdparty-openssl-3.5.5.aar
de.wellenvogel.android.ndk.thirdparty-openssl-3.5.5.ivy
de.wellenvogel.android.ndk.thirdparty-openssl-shared-3.5.5.aar
de.wellenvogel.android.ndk.thirdparty-openssl-shared-3.5.5.ivy
```

---

## GitHub Actions

Pushing to the `release` branch triggers `.github/workflows/release.yml`. It:
1. Reads all versions from `gradle/libs.versions.toml` using Python's `tomllib`
2. Installs NDK via `sdkmanager`
3. Builds both static and shared AARs and publishes to the local Ivy repository
4. Creates a GitHub release tagged with the bare OpenSSL version (e.g. `3.5.5`)
   and uploads all files from `build/repository/` as release assets

---

## AAR internal layout

**Static** (`openssl-release.aar`):
```
openssl-release.aar
├── AndroidManifest.xml     package="de.wellenvogel.android.ndk.thirdparty.openssl"
├── classes.jar             ← empty stub (AAR spec requirement)
└── prefab/
    ├── prefab.json
    └── modules/
        ├── crypto/
        │   ├── module.json               export_libraries: []
        │   ├── include/openssl/*.h
        │   └── libs/android.<abi>/       abi.json  libcrypto.a
        └── ssl/
            ├── module.json               export_libraries: [":crypto"]
            ├── include/openssl/*.h
            └── libs/android.<abi>/       abi.json  libssl.a
```

**Shared** (`openssl-shared-release.aar`) — same prefab structure with `.so`
files; additionally contains `jni/<abi>/libssl.so` and `jni/<abi>/libcrypto.so`
for AGP's standard JNI packaging pipeline.

---

## Consuming the AAR

**`build.gradle.kts`**:
```kotlin
repositories {
    ivy {
        url = uri("https://github.com/<owner>/openssl-android-aar/releases/download/3.5.5")
        patternLayout {
            artifact("[organisation]-[module]-[revision].[ext]")
            ivy("[organisation]-[module]-[revision].ivy")
        }
        metadataSources { ivyDescriptor() }
    }
}

android {
    buildFeatures { prefab = true }
}

dependencies {
    // Static:
    implementation("de.wellenvogel.android.ndk.thirdparty:openssl:3.5.5@aar")
    // Shared:
    // implementation("de.wellenvogel.android.ndk.thirdparty:openssl-shared:3.5.5@aar")
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
- `buildOpenSSL` is skipped per-ABI if the output libraries already exist
- `assemblePrefab` and `packageAar` respect Gradle's up-to-date checks

Force a full rebuild:
```bash
./gradlew clean assembleRelease
```
