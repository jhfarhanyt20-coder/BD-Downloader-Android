plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Version: set by CI from the git tag (v1.2.3 -> 1.2.3); local builds use the default.
val appVersionName: String = (System.getenv("APP_VERSION") ?: "0.1.0").removePrefix("v")
val appVersionCode: Int = appVersionName.split(".").let { p ->
    val n = p.map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    n[0] * 10000 + n[1] * 100 + n[2]
}.coerceAtLeast(1)

android {
    namespace = "com.vibecoder.vibedownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vibecoder.vibedownloader"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            // Python 3.11 is the newest version that still supports 32-bit phones
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // The signing key never lives in the repository. CI writes it from GitHub
    // Secrets into a temporary file and passes its path in SIGNING_STORE_FILE.
    val storeFilePath = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeFilePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// yt-dlp (Unlicense) runs inside the app through Chaquopy. The bundled copy is
// installed at build time; the app can also fetch a newer verified copy itself.
chaquopy {
    defaultConfig {
        version = "3.11"
        // CI points this at the Python 3.11 it installs; otherwise Chaquopy finds one on PATH
        System.getenv("VD_BUILD_PYTHON")?.takeIf { it.isNotBlank() }?.let { buildPython(it) }
        pip {
            install("yt-dlp")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // HTTP client for the download engine (Apache-2.0)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
