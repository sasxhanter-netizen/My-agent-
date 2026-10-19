plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.myagent.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.myagent.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core:1.13.1")
}
