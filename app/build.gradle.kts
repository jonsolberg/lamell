plugins {
    id("com.android.application")
}

// Release signing: the key comes from GitHub secrets (see .github/workflows/build.yml).
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "se.jonsolberg.lamell"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.jonsolberg.lamell"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk {
            // ONNX Runtime ships about 17 MB of native code per CPU type. Phones from the last
            // years are all 64-bit ARM; debug builds also get x86_64 for the CI emulator.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // The model is memory-mapped/copied as-is; compressing it only slows the first start.
        noCompress += listOf("onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.13.1")
}
