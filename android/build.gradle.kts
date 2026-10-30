buildscript {
    dependencies {
        // AGP 9 trae Kotlin INTEGRADO; así se fija la versión del compilador (2.4.20).
        // El plugin org.jetbrains.kotlin.android ya NO se aplica (error de AGP 9 si lo haces).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

// Versiones SOLO desde gradle/libs.versions.toml (regla del toolchain)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // D121: Firebase — FCM (despertador por Google Play Services) + Crashlytics
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
