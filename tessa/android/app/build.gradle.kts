plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.tessa.remote"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tessa.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
    }
    buildTypes {
        getByName("debug") {
            // Allows QA versions to coexist with previously installed
            // debug APKs signed by GitHub's ephemeral build key.
            if (providers.gradleProperty("tessaUsbQA").orNull == "true") {
                applicationIdSuffix = ".qa17"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
}
