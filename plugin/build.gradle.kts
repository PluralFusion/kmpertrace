plugins {
    kotlin("jvm") version "2.3.0"
    `java-gradle-plugin`
//    id("com.vanniktech.maven.publish") version "0.35.0"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("kmperTracePlugin") {
            id = "dev.goquick.kmpertrace.gradle"
            implementationClass = "dev.goquick.kmpertrace.KmperTracePlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

/*
mavenPublishing {
    coordinates("dev.goquick.kmpertrace", "plugin", version.toString())
    publishToMavenCentral()
    signAllPublications()

    // TODO update with your own metadata (repository, license, etc.)
    pom {
        name.set("KMP Gradle Builder Template Plugin")
        description.set("Gradle plugin that demonstrates generating shared Kotlin sources for KMP projects")
        url.set("https://github.com/goquick/kmpertrace")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("goquick")
                name.set("GoQuick")
                email.set("oss@goquick.dev")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/pluralfusion/kmpertrace.git")
            developerConnection.set("scm:git:ssh://git@github.com/pluralfusion/kmpertrace.git")
            url.set("https://github.com/pluralfusion/kmpertrace")
        }
    }
}
*/
