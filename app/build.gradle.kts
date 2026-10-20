plugins { id("com.android.application") }
android {
    namespace = "com.lovelyboutique.hafiz"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lovelyboutique.hafiz"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "5.5.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
