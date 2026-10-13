plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// true = piu veloce, richiede un telefono ARMv8.2 (circa dal 2018: Snapdragon 845+, ecc.).
// Se l'app dice che il telefono non e compatibile, metti false e ricompila.
val useFp16 = true

android {
    namespace = "it.donato.trascrittore"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.donato.trascrittore"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Solo telefoni a 64 bit (praticamente tutti quelli recenti)
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // Anche in debug il codice nativo va compilato ottimizzato,
                // altrimenti la trascrizione e 10-20 volte piu lenta.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DTRASCRITTORE_FP16=" + (if (useFp16) "ON" else "OFF"),
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
        buildConfigField("boolean", "NATIVE_FP16", useFp16.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // App per uso personale: non serve il targetSdk richiesto dal Play Store
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firma di debug: basta per installarla sul proprio telefono
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
