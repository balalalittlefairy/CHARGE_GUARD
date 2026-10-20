plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
