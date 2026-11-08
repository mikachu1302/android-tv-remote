plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mikachu1302.tvremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mikachu1302.tvremote"
        minSdk = 31
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        debug {
            // Its own app ID: the debug build installs next to the release build instead of
            // replacing it (the two are signed with different keys).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
