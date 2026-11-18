import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a keystore.properties kept OUTSIDE the repo.
// Point SHIELDVPN_KEYSTORE_PROPS at it, or use the default path below.
val keystoreProps = Properties().apply {
    val f = file(System.getenv("SHIELDVPN_KEYSTORE_PROPS")
        ?: "${System.getProperty("user.home")}/.keystores/shieldvpn/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.shieldvpn"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.shieldvpn"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-beta"
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    lint {
        // Sideloaded beta (GitHub), not published on Google Play. Play requires targetSdk 33+;
        // raising targetSdk is on the roadmap (needs foreground-service type + notification work).
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
}

dependencies {
    // Material 1.12.0 needs compileSdk 34 (installed).
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
