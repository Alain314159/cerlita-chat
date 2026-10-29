import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Firma release: android/keystore.properties (gitignored) apunta al keystore real.
// El keystore vive FUERA del repo (regla: NUNCA commitear credenciales).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "cu.alain.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "cu.alain.chat"
        minSdk = 24          // D/P1: máximo alcance; requiere desugaring
        targetSdk = 36       // D/P2: un nivel por detrás del último — comportamiento probado
        versionCode = 19
        versionName = "0.17.0"
        vectorDrawables { useSupportLibrary = true }
        // D115: la primera prueba instrumentada del proyecto (FlujoCriticoTest)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 ON desde la v1 (regla del skill: nunca activarlo "después")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } // WebRTC: -2 ABIs de APK
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests { isIncludeAndroidResources = true }
        // D115: dispositivo gestionado ATD (Automated Test Device) para la
        // instrumentada en CI — imagen AOSP recortada para pruebas, arranca
        // en ~40 s en un runner ubuntu-latest con KVM. Tarea: pixelAtdDebugAndroidTest
        // (AGP 9: el contenedor de dispositivos se llama localDevices)
        managedDevices {
            localDevices {
                create("pixelAtd") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    require64Bit = true
                }
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/**"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.tink.android)
    implementation(libs.argon2kt)
    implementation(libs.androidx.exifinterface) // rotación EXIF al comprimir fotos (D76)
    implementation(libs.stream.webrtc)         // llamadas audio/vídeo (D75): libwebrtc

    testImplementation(kotlin("test")) // tests JVM puros de cripto (sin Android)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // JSONObject/JSONArray reales en JVM (import/export)

    // Smoke test de arranque (Robolectric): reproduce un cold-start real en JVM
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    // D115: backend simulado (módulo :mockbackend) — validado en JVM por
    // MockBackendTest en cada run de tests, reutilizado por la instrumentada
    testImplementation(project(":mockbackend"))

    // D115: la primera prueba instrumentada — flujo crítico en dispositivo real
    androidTestImplementation(project(":mockbackend"))
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    // Robolectric baja los android-all jars de Maven Central en runtime:
    // apuntarlo al espejo de Google (repo1.maven.org devuelve 429 a esta IP)
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    systemProperty("robolectric.dependency.repo.id", "google-mirror")
    maxHeapSize = "2g"
}
