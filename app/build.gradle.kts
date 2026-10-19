plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val compileProtocolChecks by tasks.registering(JavaCompile::class) {
    dependsOn("compileDebugKotlin")
    source(fileTree("src/protocolCheck/java") { include("**/*.java") })
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) +
        files(configurations.getByName("debugRuntimeClasspath").filter { it.extension == "jar" })
    destinationDirectory.set(layout.buildDirectory.dir("protocol-checks"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.register<JavaExec>("protocolCheck") {
    dependsOn(compileProtocolChecks)
    classpath = compileProtocolChecks.get().classpath + files(compileProtocolChecks.get().destinationDirectory)
    mainClass.set("ProtocolChecks")
}

android {
    namespace = "io.github.chargercontroller"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.chargercontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.4.2"
        testInstrumentationRunner = "io.github.chargercontroller.SmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
