plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sadesocks.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sadesocks.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
