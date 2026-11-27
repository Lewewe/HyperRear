import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// HyperMusicCover-Enhanced's key, from the git-ignored keystore.properties or CI's environment.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStorePath = signingValue("storeFile", "SIGNING_STORE_FILE")

android {
    namespace = "com.os4.rear"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yzc26623.HyperRear"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.0-alpha"
    }
    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Same identity as HyperMusicCover-Enhanced, which feeds and configures it. A clone
            // with no key falls back to debug, which only matches a debug-signed HMC-E.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
