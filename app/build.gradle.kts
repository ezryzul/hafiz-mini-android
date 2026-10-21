plugins { id("com.android.application") }
android {
    namespace = "com.lovelyboutique.hafiz"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lovelyboutique.hafiz"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "5.6.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
