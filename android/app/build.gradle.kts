plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ksdfs1.weeklyroutine"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ksdfs1.weeklyroutine"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // sideloaded app: sign release builds with the debug key unless you set up your own
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
