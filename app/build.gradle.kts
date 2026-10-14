plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma di rilascio: se le variabili d'ambiente sono impostate (in GitHub Actions
// arrivano dai secrets del repository) l'APK e firmato sempre con la stessa chiave,
// cosi le nuove versioni si installano sopra le vecchie senza disinstallare.
// Senza variabili si usa la chiave di debug del PC, come prima.
val releaseKeystore: String? = System.getenv("TRASCRITTORE_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "it.donato.trascrittore"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "it.donato.trascrittore"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("TRASCRITTORE_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("TRASCRITTORE_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.2"

        // Solo telefoni a 64 bit (praticamente tutti quelli recenti)
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                // Anche in debug il codice nativo va compilato ottimizzato,
                // altrimenti la trascrizione e 10-20 volte piu lenta.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    packaging {
        jniLibs {
            // ggml cerca le varianti libggml-cpu-*.so elencando la cartella delle
            // librerie native: devono essere estratte dall'APK all'installazione.
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("TRASCRITTORE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRASCRITTORE_KEY_ALIAS")
                keyPassword = System.getenv("TRASCRITTORE_KEY_PASSWORD")
            }
        }
    }

    lint {
        // App per uso personale: non serve il targetSdk richiesto dal Play Store
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
