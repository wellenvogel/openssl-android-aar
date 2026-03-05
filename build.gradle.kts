import org.gradle.api.GradleException
import java.net.URI
import java.security.MessageDigest

plugins {
    `ivy-publish`
}

group   = "de.wellenvogel.android.ndk.thirdparty"
version = "${libs.versions.openssl.get()}-1"

// ─────────────────────────────────────────────────────────────────────────────
// Build configuration
// ─────────────────────────────────────────────────────────────────────────────
val opensslVersion   = libs.versions.openssl.get()
val ndkVersion       = libs.versions.ndkVersion.get()
val minSdkVersion    = libs.versions.minSdk.get().toInt()
val sdkToolsVersion  = libs.versions.sdkToolsVersion.get()
val abis             = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")

// ─────────────────────────────────────────────────────────────────────────────
// Build type property: -PbuildShared=true  → shared (.so)
//                      default             → static (.a)
//
// Usage:
//   ./gradlew :assembleRelease                 # static (default)
//   ./gradlew :assembleRelease -PbuildShared=true  # shared
// ─────────────────────────────────────────────────────────────────────────────
val buildShared: Boolean = (findProperty("buildShared") as String?)
    ?.trim()?.lowercase() == "true"

val libType = if (buildShared) "shared" else "static"

// Adjust Maven artifact ID so static and shared can coexist in the same repo
val artifactId = if (buildShared) "openssl-shared" else "openssl"

val tarballBaseUrl = "https://github.com/openssl/openssl/releases/download/openssl-$opensslVersion"
val moduleDir      = projectDir
val opensslOut     = moduleDir.resolve("src/main/cpp/openssl")
val scratchDir     = layout.buildDirectory.dir("openssl-build").get().asFile

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(65536)
        var n = ins.read(buf)
        while (n != -1) { digest.update(buf, 0, n); n = ins.read(buf) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun fetchText(url: String): String =
    URI(url).toURL().openConnection().also {
        it.setRequestProperty("User-Agent", "Gradle/openssl-android-aar")
        it.connect()
    }.getInputStream().bufferedReader().readText().trim()

fun download(url: String, dest: File) {
    logger.lifecycle("  Downloading $url")
    var conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "Gradle/openssl-android-aar")
    repeat(5) {
        val code = conn.responseCode
        if (code in 301..308) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URI(loc).toURL().openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Gradle/openssl-android-aar")
        }
    }
    conn.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
}

fun resolveNdk(): File {
    for (envVar in listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")) {
        val v = System.getenv(envVar) ?: continue
        val f = File(v)
        if (f.resolve("source.properties").exists()) {
            logger.lifecycle("NDK found via \$$envVar: $f"); return f
        }
    }
    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home")?.let { "$it/Library/Android/sdk" },
        System.getProperty("user.home")?.let { "$it/Android/Sdk" },
        "/opt/android/sdk"
    )
    for (sdkRoot in sdkRoots) {
        File("$sdkRoot/ndk/$ndkVersion").takeIf { it.resolve("source.properties").exists() }
            ?.let { logger.lifecycle("NDK found: $it"); return it }
        File("$sdkRoot/ndk").takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.name.startsWith("29.") && it.resolve("source.properties").exists() }
            ?.maxByOrNull { it.name }
            ?.let { logger.lifecycle("NDK 29.x found: $it"); return it }
    }
    for (sdkRoot in sdkRoots) {
        val sdkManager = listOf(
            "$sdkRoot/cmdline-tools/latest/bin/sdkmanager",
            "$sdkRoot/cmdline-tools/$sdkToolsVersion/bin/sdkmanager",
            "$sdkRoot/tools/bin/sdkmanager"
        ).map(::File).firstOrNull(File::exists) ?: continue
        logger.lifecycle("Installing NDK $ndkVersion via sdkmanager ...")
        val rc = ProcessBuilder(sdkManager.absolutePath, "--install", "ndk;$ndkVersion")
            .inheritIO().start().waitFor()
        if (rc != 0) throw GradleException("sdkmanager exited $rc")
        File("$sdkRoot/ndk/$ndkVersion").takeIf { it.resolve("source.properties").exists() }
            ?.let { return it }
    }
    throw GradleException("""
        |Android NDK $ndkVersion not found and could not be installed automatically.
        |Options:
        |  A) Install via Android Studio: SDK Manager → SDK Tools → NDK (Side by side) → $ndkVersion
        |  B) export ANDROID_NDK_HOME=/path/to/ndk/$ndkVersion
        |  C) sdkmanager "ndk;$ndkVersion"
        """.trimMargin())
}

fun opensslTarget(abi: String) = when (abi) {
    "arm64-v8a"   -> "android-arm64"
    "armeabi-v7a" -> "android-arm"
    "x86_64"      -> "android-x86_64"
    "x86"         -> "android-x86"
    else          -> throw GradleException("Unknown ABI: $abi")
}

fun clangForAbi(abi: String, api: Int) = when (abi) {
    "arm64-v8a"   -> "aarch64-linux-android${api}-clang"
    "armeabi-v7a" -> "armv7a-linux-androideabi${api}-clang"
    "x86_64"      -> "x86_64-linux-android${api}-clang"
    "x86"         -> "i686-linux-android${api}-clang"
    else          -> throw GradleException("Unknown ABI: $abi")
}

fun hostTag(): String {
    val os   = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("linux")                                                    -> "linux-x86_64"
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) -> "darwin-arm64"
        os.contains("mac")                                                      -> "darwin-x86_64"
        else -> throw GradleException("Unsupported host OS: $os")
    }
}

fun exec(workDir: File, env: Map<String, String>, vararg cmd: String) {
    logger.lifecycle("  > ${cmd.joinToString(" ")}")
    val pb = ProcessBuilder(*cmd).directory(workDir).redirectErrorStream(true)
    pb.environment().putAll(env)
    val proc = pb.start()
    proc.inputStream.bufferedReader().forEachLine { logger.info(it) }
    val rc = proc.waitFor()
    if (rc != 0) throw GradleException("Command failed (exit $rc): ${cmd.first()}")
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: downloadOpenSSL
// ─────────────────────────────────────────────────────────────────────────────
val tarballName = "openssl-${opensslVersion}.tar.gz"
val tarballFile = scratchDir.resolve(tarballName)

val downloadOpenSSL by tasks.registering {
    outputs.file(tarballFile)
    outputs.upToDateWhen {
        if (!tarballFile.exists()) return@upToDateWhen false
        val expected = try {
            fetchText("$tarballBaseUrl/$tarballName.sha256").split("\\s".toRegex()).first()
        } catch (e: Exception) {
            logger.warn("Could not fetch checksum: ${e.message}"); return@upToDateWhen true
        }
        sha256(tarballFile) == expected
    }

    doLast {
        scratchDir.mkdirs()
        logger.lifecycle("Fetching checksum for $tarballName ...")
        val expectedSha256 = fetchText("$tarballBaseUrl/$tarballName.sha256")
            .split("\\s".toRegex()).first()
        logger.lifecycle("Expected SHA-256: $expectedSha256")

        if (tarballFile.exists() && sha256(tarballFile) == expectedSha256) {
            logger.lifecycle("$tarballName already downloaded and verified — skipping."); return@doLast
        }

        download("$tarballBaseUrl/$tarballName", tarballFile)

        val actual = sha256(tarballFile)
        if (actual != expectedSha256) {
            tarballFile.delete()
            throw GradleException("SHA-256 mismatch for $tarballName\n  expected: $expectedSha256\n  actual:   $actual")
        }
        logger.lifecycle("$tarballName downloaded and verified.")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: buildOpenSSL
//
// Static build:  produces  libs/<abi>/libssl.a  +  libcrypto.a
// Shared build:  produces  libs/<abi>/libssl.so +  libcrypto.so
//                          (versioned symlinks are stripped; only the plain .so is kept)
//
// Configure flags are clean — only feature flags as positional args.
// CC, CFLAGS, CXXFLAGS, LDFLAGS are all passed via environment (OpenSSL 3.5.x requirement).
//
// 16 KB page-size:  OpenSSL 3.5.x already sets -Wl,-z,max-page-size=16384 in its
//   android-arm64 / android-x86_64 platform configs (PR #28277).  We also pass it
//   explicitly via LDFLAGS for the remaining ABIs and to make the intent clear.
// ─────────────────────────────────────────────────────────────────────────────
val buildOpenSSL by tasks.registering {
    dependsOn(downloadOpenSSL)
    outputs.dir(opensslOut)
    outputs.upToDateWhen {
        val libExt = if (buildShared) "so" else "a"
        abis.all { abi ->
            opensslOut.resolve("libs/$abi/libssl.$libExt").exists() &&
            opensslOut.resolve("libs/$abi/libcrypto.$libExt").exists()
        } && opensslOut.resolve("include/openssl/ssl.h").exists()
    }

    doLast {
        logger.lifecycle("Build type: $libType")
        val ndkRoot   = resolveNdk()
        val tag       = hostTag()
        val toolchain = ndkRoot.resolve("toolchains/llvm/prebuilt/$tag")
        val jobs      = Runtime.getRuntime().availableProcessors().toString()

        logger.lifecycle("NDK:       $ndkRoot")
        logger.lifecycle("Toolchain: $toolchain")
        logger.lifecycle("CPUs:      $jobs")

        abis.forEach { abi ->
            val libDst = opensslOut.resolve("libs/$abi")
            val libExt = if (buildShared) "so" else "a"
            if (libDst.resolve("libssl.$libExt").exists() &&
                libDst.resolve("libcrypto.$libExt").exists()) {
                logger.lifecycle("[$abi] Already built ($libType) — skipping.")
                return@forEach
            }

            logger.lifecycle("")
            logger.lifecycle("━━━  $abi  (API $minSdkVersion, $libType)  ━━━")

            val srcDir  = scratchDir.resolve("openssl-${opensslVersion}-${abi}-${libType}")
            val instDir = scratchDir.resolve("install-${abi}-${libType}")
            srcDir.deleteRecursively()
            instDir.deleteRecursively()

            exec(scratchDir, emptyMap(),
                "tar", "xzf", tarballFile.absolutePath, "-C", scratchDir.absolutePath)
            scratchDir.resolve("openssl-$opensslVersion").renameTo(srcDir)

            val clang    = clangForAbi(abi, minSdkVersion)
            val cFlags   = "-ffunction-sections -fdata-sections -fstack-protector-strong -D_FORTIFY_SOURCE=2"
            // Shared libs must be position-independent; -fPIC is the NDK default but we
            // set it explicitly. Static libs are linked into the consumer's binary which
            // needs max-page-size; we set it for both to keep the flags symmetric.
            val ldFlags  = if (buildShared)
                "-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--no-undefined"
            else
                "-Wl,-z,max-page-size=16384"

            val toolEnv = mapOf(
                "ANDROID_NDK_ROOT" to ndkRoot.absolutePath,
                "PATH"             to "${toolchain.resolve("bin")}:${System.getenv("PATH")}",
                "CC"               to toolchain.resolve("bin/$clang").absolutePath,
                "CFLAGS"           to cFlags,
                "CXXFLAGS"         to cFlags,
                "LDFLAGS"          to ldFlags
            )

            // Configure positional args: only feature flags, no make variables
            val configureArgs = mutableListOf(
                srcDir.resolve("Configure").absolutePath,
                opensslTarget(abi),
                if (buildShared) "shared" else "no-shared",
                "no-tests",
                "no-apps",
                "no-docs",
                "no-engine",
                "no-dynamic-engine",
                "--prefix=${instDir.absolutePath}",
                "--openssldir=${instDir.resolve("ssl").absolutePath}",
                "-D__ANDROID_API__=$minSdkVersion"
            )

            exec(srcDir, toolEnv, *configureArgs.toTypedArray())

            if (buildShared) {
                // Build everything (shared libs + support objects)
                exec(srcDir, toolEnv, "make", "-j$jobs")
                exec(srcDir, toolEnv, "make", "install")
            } else {
                // Static: only build and install the library archives
                exec(srcDir, toolEnv, "make", "-j$jobs", "build_libs")
                exec(srcDir, toolEnv, "make", "install_dev")
            }

            libDst.mkdirs()

            if (buildShared) {
                // Copy the plain .so files (not the versioned symlinks like libssl.so.3)
                // The NDK shared lib convention is libssl.so / libcrypto.so with no version suffix.
                for (lib in listOf("libssl", "libcrypto")) {
                    val soFile = instDir.resolve("lib/$lib.so")
                    if (!soFile.exists()) {
                        // OpenSSL installs versioned names; find and copy the real file
                        val versioned = instDir.resolve("lib").listFiles()
                            ?.filter { it.name.startsWith("$lib.so.") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                            ?.maxByOrNull { it.name }
                            ?: throw GradleException("Could not find $lib.so in ${instDir.resolve("lib")}")
                        versioned.copyTo(libDst.resolve("$lib.so"), overwrite = true)
                    } else {
                        soFile.copyTo(libDst.resolve("$lib.so"), overwrite = true)
                    }
                }
            } else {
                instDir.resolve("lib/libssl.a").copyTo(libDst.resolve("libssl.a"), overwrite = true)
                instDir.resolve("lib/libcrypto.a").copyTo(libDst.resolve("libcrypto.a"), overwrite = true)
            }

            if (!opensslOut.resolve("include/openssl/ssl.h").exists()) {
                instDir.resolve("include").copyRecursively(opensslOut.resolve("include"), overwrite = true)
                logger.lifecycle("Headers installed → ${opensslOut.resolve("include")}")
            }

            logger.lifecycle("[$abi] ✓  lib{ssl,crypto}.${libExt} → $libDst")
        }

        logger.lifecycle("\nOpenSSL $opensslVersion build complete ($libType).")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: assemblePrefab
//
// Static:  abi.json has "static": true,  libs contain .a files
// Shared:  abi.json has "static": false, libs contain .so files
//          shared modules don't need export_libraries (runtime linking handles deps)
// ─────────────────────────────────────────────────────────────────────────────
val assemblePrefab by tasks.registering {
    dependsOn(buildOpenSSL)
    outputs.dir(layout.buildDirectory.dir("prefab"))

    doLast {
        val prefabRoot = layout.buildDirectory.dir("prefab").get().asFile
        prefabRoot.deleteRecursively()
        prefabRoot.mkdirs()

        prefabRoot.resolve("prefab.json").writeText(
            """{"schema_version":2,"name":"openssl","version":"$opensslVersion","dependencies":[]}"""
        )

        listOf("crypto", "ssl").forEach { module ->
            val modDir = prefabRoot.resolve("modules/$module")
            modDir.mkdirs()

            // Shared libs resolve their own dependencies at runtime via DT_NEEDED;
            // export_libraries is only needed for static libs where the linker must
            // be told to pull in transitive deps.
            val exportLibs = if (!buildShared && module == "ssl") """[":crypto"]""" else "[]"
            modDir.resolve("module.json").writeText(
                """{"export_libraries":$exportLibs,"android":{"export_libraries":$exportLibs}}"""
            )

            opensslOut.resolve("include")
                .copyRecursively(modDir.resolve("include"), overwrite = true)

            val libExt      = if (buildShared) "so" else "a"
            val libFileName = "lib${module}.$libExt"

            abis.forEach { abi ->
                val libDir = modDir.resolve("libs/android.$abi")
                libDir.mkdirs()
                libDir.resolve("abi.json").writeText(
                    """{
  "abi": "$abi",
  "api": $minSdkVersion,
  "ndk": 29,
  "stl": "none",
  "static": ${!buildShared}
}"""
                )
                opensslOut.resolve("libs/$abi/$libFileName")
                    .copyTo(libDir.resolve(libFileName), overwrite = true)
            }
        }

        logger.lifecycle("Prefab package assembled ($libType): ${prefabRoot.absolutePath}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// emptyJar — classes.jar stub required by the AAR spec
// ─────────────────────────────────────────────────────────────────────────────
val emptyJar by tasks.registering(Jar::class) {
    archiveFileName.set("classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/empty_jar"))
}

// ─────────────────────────────────────────────────────────────────────────────
// Task: packageAar
//
// AAR layout:
//   AndroidManifest.xml
//   classes.jar
//   prefab/          ← always present (Prefab package)
//   jni/<abi>/*.so   ← only for shared builds (so the AAR is self-contained;
//                       the .so files are also inside prefab/ for CMake consumers,
//                       but jni/ makes them available to Gradle's standard JNI
//                       packaging pipeline for APK/AAB bundling)
// ─────────────────────────────────────────────────────────────────────────────
val aarName = if (buildShared) "openssl-shared-release.aar" else "openssl-release.aar"

val packageAar by tasks.registering(Zip::class) {
    dependsOn(assemblePrefab, emptyJar)

    archiveFileName.set(aarName)
    destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))

    from(moduleDir.resolve("src/main/AndroidManifest.xml"))
    from(emptyJar.get().archiveFile)
    from(layout.buildDirectory.dir("prefab")) { into("prefab") }

    // For shared builds also include the .so files under jni/<abi>/ so that
    // AGP's standard mergeJniLibs task picks them up when building the APK/AAB.
    if (buildShared) {
        abis.forEach { abi ->
            from(opensslOut.resolve("libs/$abi")) {
                include("*.so")
                into("jni/$abi")
            }
        }
    }
}

tasks.register("assembleRelease") { dependsOn(packageAar) }
tasks.register("assemble")        { dependsOn(packageAar) }

// ─────────────────────────────────────────────────────────────────────────────
// Ivy publishing — publishes to a local Ivy repository.
//
// Repository layout (ivy-default pattern):
//   build/repository/
//     de.wellenvogel.android.ndk.thirdparty/
//       openssl[-shared]/
//         3.5.5-1/
//           ivy-3.5.5-1.xml           ← Ivy descriptor
//           openssl[-shared]-3.5.5-1.aar
//
// To consume from another Gradle project add:
//   repositories {
//     ivy {
//       url = uri("/path/to/openssl-android-aar/openssl/build/repository")
//       patternLayout { artifact("[organisation]/[module]/[revision]/[artifact]-[revision].[ext]") }
//       metadataSources { ivyDescriptor() }
//     }
//   }
//   dependencies {
//     implementation("de.wellenvogel.android.ndk.thirdparty:openssl:3.5.5-1@aar")
//   }
// ─────────────────────────────────────────────────────────────────────────────
publishing {
    publications {
        create<IvyPublication>("release") {
            organisation = project.group.toString()
            module       = artifactId
            revision     = project.version.toString()

            artifact(packageAar.get().archiveFile) {
                builtBy(packageAar)
                name      = artifactId
                extension = "aar"
                type      = "aar"
            }

            descriptor {
                description {
                    text.set(
                        "OpenSSL $opensslVersion for Android — Prefab AAR " +
                        "($libType, NDK $ndkVersion, 16 KB page-size)"
                    )
                }
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.openssl.org/source/license.html")
                }
            }
        }
    }

    repositories {
        ivy {
            name = "local"
            url  = uri(layout.buildDirectory.dir("repository"))
            patternLayout {
                artifact("[organisation]/[module]/[revision]/[artifact]-[revision].[ext]")
                ivy("[organisation]/[module]/[revision]/ivy-[revision].xml")
            }
        }
    }
}
