plugins {
    // declare the versions ONCE here
    kotlin("jvm") version "2.3.0" apply false
    kotlin("multiplatform") version "2.3.0" apply false // if your sample will be KMP
    kotlin("android") version "2.3.0" apply false
    id("org.jetbrains.compose") version "1.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("com.android.application") version "9.0.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.0" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

val kmpertraceGroup: String by project
val kmpertraceVersion: String by project

repositories {
    mavenCentral()
}


allprojects {
    group = kmpertraceGroup
    version = kmpertraceVersion

    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
