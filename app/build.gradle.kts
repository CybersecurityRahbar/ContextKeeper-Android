plugins {
    id("com.android.application")
}

android {
    namespace = "com.cyberphantom.contextkeeper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cyberphantom.contextkeeper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
}
