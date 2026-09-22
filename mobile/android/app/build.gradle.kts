import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val previewKeystoreSource = rootProject.file("preview-keystore.b64")
val previewKeystoreFile = layout.buildDirectory.file("preview-signing/telemetry-preview.jks").get().asFile
if (!previewKeystoreFile.exists() && previewKeystoreSource.exists()) {
    previewKeystoreFile.parentFile.mkdirs()
    previewKeystoreFile.writeBytes(Base64.getMimeDecoder().decode(previewKeystoreSource.readText().trim()))
}

android {
    namespace = "com.telemetry.app"
    compileSdk = 37

    signingConfigs {
        create("preview") {
            storeFile = previewKeystoreFile
            storePassword = "telemetrypreview"
            keyAlias = "telemetry-preview"
            keyPassword = "telemetrypreview"
        }
    }

    defaultConfig {
        applicationId = "com.telemetry.preview"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("TELEMETRY_VERSION_CODE")?.toIntOrNull() ?: 5
        versionName = System.getenv("TELEMETRY_VERSION_NAME") ?: "0.5.0-preview"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("preview")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
}
