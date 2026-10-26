plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.gmercier.bluelock"
    compileSdk = 34

    defaultConfig {
        applicationId = "ca.gmercier.bluelock"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    // Clé de signature personnelle (fixe) : permet d'installer les mises à jour
    // par-dessus l'ancienne version sans perdre tes réglages.
    signingConfigs {
        create("perso") {
            storeFile = file("../keystore/bluelock.jks")
            storePassword = "bluelockperso"
            keyAlias = "bluelock"
            keyPassword = "bluelockperso"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
        debug {
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Aucune dépendance externe : uniquement le SDK Android et Kotlin.
dependencies {
}
