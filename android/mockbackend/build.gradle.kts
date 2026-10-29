plugins {
    // AGP 9 trae el plugin de Kotlin embebido en su classpath: aplicar SIN
    // versión (pedirla otra vez rompe la resolución del build).
    id("org.jetbrains.kotlin.jvm")
}

// D115: backend simulado compartido por los tests JVM (MockBackendTest, sin
// emulador) y la primera prueba instrumentada (FlujoCriticoTest, en el
// dispositivo). Módulo aparte porque el Kotlin integrado de AGP 9 no admite
// source sets compartidos entre test y androidTest.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // server (MockWebServer) es parte de la API pública de MockBackend
    api("com.squareup.okhttp3:mockwebserver:5.5.1")

    // org.json: la aportan los tests JVM (org.json:json) y Android la trae de
    // serie — compileOnly para NO arrastrar el jar al classpath androidTest
    // (clases duplicadas con la plataforma).
    compileOnly("org.json:json:20240303")
}
