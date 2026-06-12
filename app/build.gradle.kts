plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bconf.tunnellight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bconf.tunnellight"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val ksPath    = System.getenv("KEYSTORE_PATH")
            val ksAlias   = System.getenv("KEY_ALIAS")
            val ksKeyPw   = System.getenv("KEY_PASSWORD")
            val ksStorePw = System.getenv("STORE_PASSWORD")
            if (!ksPath.isNullOrBlank() && !ksAlias.isNullOrBlank()) {
                storeFile     = file(ksPath)
                keyAlias      = ksAlias
                keyPassword   = ksKeyPw
                storePassword = ksStorePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/BC*.SF"
            excludes += "META-INF/BC*.DSA"
            excludes += "META-INF/BC*.RSA"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.github.mwiede:jsch:0.2.17")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
}
