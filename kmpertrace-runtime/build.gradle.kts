import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.io.ByteArrayOutputStream
import java.io.FileFilter
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    id("com.vanniktech.maven.publish")
}

val kmpertraceGroup: String by project
val kmpertraceVersion: String by project

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    val xcf = XCFramework("KmperTraceRuntime")

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-opt-in=kotlin.time.ExperimentalTime"
        )
    }

    androidLibrary {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        namespace = "dev.goquick.kmpertrace.runtime"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )
    linuxX64()
    macosArm64()
    macosX64()
    mingwX64()

    wasmJs {
        browser()
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinxCoroutinesCore)
            api(libs.kotlinxDatetime)
        }
        iosMain.dependencies {
            implementation(libs.kotlinxCoroutinesCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinxCoroutinesTest)
        }
        wasmJsMain.dependencies {
            implementation(npm("@js-joda/core", "5.5.2"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "KmperTraceRuntime"
            isStatic = true
            xcf.add(this)
        }
    }
}

// Ensure native frameworks export our dependencies when consumed from other KMP/iOS modules.
kotlin.targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).all {
    binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.Framework::class.java).all {
        export(libs.kotlinxDatetime)
        export(libs.kotlinxCoroutinesCore)
    }
}

tasks.register("prepareSpmRelease") {
    dependsOn("assembleKmperTraceRuntimeReleaseXCFramework")
    group = "distribution"
    description = "Build release XCFramework, zip it, compute checksum, and render Package.swift for SwiftPM binary distribution."

    doLast {
        val version: String by project
        val releaseDir = layout.buildDirectory.dir("XCFrameworks/release").get()
        val xcframeworkDir = releaseDir.dir("KmperTraceRuntime.xcframework")
        check(xcframeworkDir.asFile.exists()) {
            "XCFramework not found at ${xcframeworkDir.asFile}. Run assembleKmperTraceRuntimeReleaseXCFramework first."
        }

        val zipFile = releaseDir.file("KmperTraceRuntime.xcframework.zip")
        zipFile.asFile.delete()
        xcframeworkDir.asFile
            .walkTopDown()
            .filter { it.isDirectory && it.name.endsWith(".framework") }
            .forEach { frameworkDir ->
                val lib = frameworkDir.resolve(frameworkDir.name.removeSuffix(".framework"))
                if (!lib.isFile) return@forEach
                val tmpdir = Files.createTempDirectory("kmpertrace-ar-").toFile()
                providers.exec {
                    workingDir = tmpdir
                    commandLine("ar", "-x", lib.absolutePath)
                }.result.get()
                tmpdir.listFiles(FileFilter { file -> file.name.startsWith("__.SYMDEF") })
                    ?.forEach { it.delete() }
                val objFiles = tmpdir.listFiles(FileFilter { file -> file.extension == "o" })
                    ?.map { it.name }
                    .orEmpty()
                check(objFiles.isNotEmpty()) { "No object files extracted from ${lib.absolutePath}" }
                lib.delete()
                providers.exec {
                    workingDir = tmpdir
                    environment("ZERO_AR_DATE", "1")
                    val command = mutableListOf("ar", "-rcs", lib.absolutePath)
                    command.addAll(objFiles)
                    commandLine(command)
                }.result.get()
                tmpdir.deleteRecursively()
            }
        providers.exec {
            commandLine(
                "python3",
                "-c",
                """
                import plistlib, pathlib, sys
                path = pathlib.Path(sys.argv[1])
                data = plistlib.load(path.open("rb"))
                libs = data.get("AvailableLibraries")
                if isinstance(libs, list):
                    data["AvailableLibraries"] = sorted(
                        libs,
                        key=lambda d: d.get("LibraryIdentifier", "")
                    )
                with path.open("wb") as f:
                    plistlib.dump(data, f, sort_keys=True)
                """.trimIndent(),
                xcframeworkDir.asFile.resolve("Info.plist").absolutePath
            )
        }.result.get()
        providers.exec {
            commandLine(
                "find",
                xcframeworkDir.asFile.absolutePath,
                "-exec",
                "touch",
                "-t",
                "198001010000",
                "{}",
                "+"
            )
        }.result.get()
        providers.exec {
            workingDir = releaseDir.asFile
            commandLine("zip", "-r", "-X", zipFile.asFile.name, xcframeworkDir.asFile.name)
        }.result.get()

        val checksumOutput = ByteArrayOutputStream()
        providers.exec {
            workingDir = releaseDir.asFile
            commandLine("shasum", "-a", "256", zipFile.asFile.name)
            standardOutput = checksumOutput
        }.result.get()
        val checksum = checksumOutput.toString().trim().split(" ").firstOrNull()
            ?: error("Failed to parse checksum")

        val template = rootProject.file("Package.swift.template").readText()
        val url = "https://github.com/pluralfusion/kmpertrace/releases/download/v$version/KmperTraceRuntime.xcframework.zip"
        val rendered = template
            .replace("__URL__", url)
            .replace("__CHECKSUM__", checksum)

        rootProject.file("Package.swift").writeText(rendered)

        println("[KmperTrace] Prepared Package.swift for v$version")
        println("[KmperTrace] URL: $url")
        println("[KmperTrace] SHA-256: $checksum")
    }
}

// Work around Kotlin/Native test runner instability with TeamCity service messages on iOS simulators.
// Without this, occasional segfaults/“Received output for test that is not running” can surface on fresh daemons/CI.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KOTLIN_NATIVE_IGNORE_SERVICE_MESSAGES", "true")
}

mavenPublishing {
    coordinates(kmpertraceGroup, "kmpertrace-runtime", kmpertraceVersion)
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("KmperTrace Runtime")
        description.set("Multiplatform runtime for KmperTrace logging and tracing")
        url.set("https://github.com/pluralfusion/kmpertrace")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id = "mobiletoly"
                name = "Toly Pochkin"
                url = "https://github.com/mobiletoly"
            }
        }
        scm {
            connection = "scm:git:https://github.com/pluralfusion/kmpertrace.git"
            developerConnection = "scm:git:ssh://git@github.com/pluralfusion/kmpertrace.git"
            url = "https://github.com/pluralfusion/kmpertrace"
        }
    }
}
