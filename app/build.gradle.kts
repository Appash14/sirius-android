plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "fr.tom.sirius"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.tom.sirius"
        minSdk = 31
        targetSdk = 36
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "2.12"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // Clé de signature fixe (secret CI DEBUG_KEYSTORE_B64 décodé dans SIRIUS_DEBUG_KEYSTORE) : les mises à jour
    // s'installent par-dessus l'appli existante. Sans la variable (poste local), clé debug par défaut.
    signingConfigs {
        getByName("debug") {
            System.getenv("SIRIUS_DEBUG_KEYSTORE")?.let { chemin ->
                storeFile = file(chemin)
                storeType = "pkcs12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.maxHeapSize = "2g"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.2")
    implementation("androidx.savedstate:savedstate:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
}
