plugins { id("com.android.application") }

android {
    namespace = "com.rmambulancier.nova"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rmambulancier.nova"
        minSdk = 29
        targetSdk = 37
        versionCode = 6
        versionName = "5.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
