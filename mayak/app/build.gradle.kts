plugins { id("com.android.application") }

android {
    namespace = "ru.mayak.client"
    compileSdk = 36
    defaultConfig { applicationId = "ru.mayak.client"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "0.2.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies { implementation("androidx.core:core-ktx:1.17.0") }
