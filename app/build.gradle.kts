plugins {
    id("com.android.application")
}

android {
    namespace = "com.lovelyboutique.hafiz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lovelyboutique.hafiz"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
