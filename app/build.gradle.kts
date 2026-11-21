plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun signingValue(property: String, environment: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orNull

val releaseStore = signingValue("flick.signing.storeFile", "FLICK_KEYSTORE_FILE")
val releaseStorePassword = signingValue("flick.signing.storePassword", "FLICK_KEYSTORE_PASSWORD")
val releaseAlias = signingValue("flick.signing.keyAlias", "FLICK_KEY_ALIAS")
val releaseKeyPassword = signingValue("flick.signing.keyPassword", "FLICK_KEY_PASSWORD")
val releaseSigning = listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword)
val signingConfigured = releaseSigning.any { it != null }
require(!signingConfigured || releaseSigning.all { !it.isNullOrBlank() }) {
    "Provide all four Flick signing settings, or omit them to build an unsigned release."
}

android {
    namespace = "app.flick.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.flick.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 235
        versionName = "0.2.35"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (signingConfigured) create("release") {
            storeFile = file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
