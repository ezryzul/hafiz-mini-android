plugins { id("com.android.application") }
android {
    namespace = "com.lovelyboutique.hafiz"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.lovelyboutique.hafiz"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "5.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
