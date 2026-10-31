plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 每次在 GitHub 編譯時版本號自動加一，手機才能直接更新到新版
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// 正式簽章：金鑰放在 GitHub Secrets，編譯時由 main.yml 提供
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "com.example.sleepwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.liaomfdev.sleepwatch"
        minSdk = 24
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null && keystorePassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "release"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
