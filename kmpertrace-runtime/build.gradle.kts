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

// Compile the Kotlin code blocks in the docs so they cannot drift from the API.
// A block fenced as ```kotlin compile is compiled (JVM) against the runtime. A block fenced as
// ```kotlin fragment is skipped. A kotlin block with neither marker fails the build.
// Blocks of one file are compiled together, in order, and may use these imports without writing them.
val docSnippetFiles = listOf("README.md", "docs/AI-assistants.md", "docs/recipes.md", "docs/migration.md")
val docSnippetPrelude = listOf(
    "dev.goquick.kmpertrace.core.Level",
    "dev.goquick.kmpertrace.log.KmperTrace",
    "dev.goquick.kmpertrace.log.Log",
    "dev.goquick.kmpertrace.log.journey",
    "dev.goquick.kmpertrace.trace.TraceTrigger",
    "dev.goquick.kmpertrace.trace.captureTraceSnapshot",
    "dev.goquick.kmpertrace.trace.traceSpan",
    "dev.goquick.kmpertrace.trace.withTraceContext",
    "kotlinx.coroutines.CoroutineDispatcher",
)

val generateDocSnippets by tasks.registering {
    group = "verification"
    description = "Extracts '```kotlin compile' blocks from the docs into a generated test source file."
    val rootDir = rootProject.layout.projectDirectory.asFile
    val outDir = layout.buildDirectory.dir("generated/docSnippets/kotlin")
    inputs.files(docSnippetFiles.map { File(rootDir, it) })
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        docSnippetFiles.forEach { rel ->
            val file = File(rootDir, rel)
            if (!file.exists()) return@forEach
            val bodies = mutableListOf<String>()
            val imports = LinkedHashSet<String>(docSnippetPrelude.map { "import $it" })
            var ticks = 0
            var indent = ""
            var marker: String? = null
            var current = mutableListOf<String>()
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (ticks == 0) {
                    if (trimmed.startsWith("```")) {
                        ticks = trimmed.takeWhile { it == '`' }.length
                        indent = line.takeWhile { it == ' ' }
                        val info = trimmed.drop(ticks).trim().split(Regex("\\s+"))
                        marker = when {
                            info[0] != "kotlin" -> "other"
                            info.getOrNull(1) == "compile" -> "compile"
                            info.getOrNull(1) == "fragment" -> "fragment"
                            else -> throw GradleException("$rel:${index + 1}: kotlin block needs 'compile' or 'fragment' after the language")
                        }
                        current = mutableListOf()
                    }
                } else if (trimmed == "`".repeat(ticks)) {
                    if (marker == "compile") bodies += current.joinToString("\n")
                    ticks = 0
                } else if (marker == "compile") {
                    val text = line.removePrefix(indent)
                    if (text.startsWith("import ")) imports += text.trim() else current += text
                }
            }
            if (bodies.isNotEmpty()) {
                val name = rel.substringAfterLast('/').substringBeforeLast('.').lowercase().filter { it.isLetter() }
                val dir = File(out, "docsnippets").also { it.mkdirs() }
                File(dir, "$name.kt").writeText(
                    "package docsnippets.$name\n\n" + imports.joinToString("\n") + "\n\n" + bodies.joinToString("\n\n") + "\n"
                )
            }
        }
    }
}

kotlin.sourceSets.named("jvmTest") { kotlin.srcDir(generateDocSnippets) }

tasks.register("checkDocSnippets") {
    group = "verification"
    description = "Compiles the Kotlin code blocks marked 'compile' in README.md and docs/."
    dependsOn("compileTestKotlinJvm")
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
