plugins {
    id("com.android.application")
}

android {
    namespace = "com.ugwap.dynamicstatusbar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ugwap.dynamicstatusbar"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
}
