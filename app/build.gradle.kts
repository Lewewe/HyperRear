plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.os4.rear"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yzc26623.HyperRear"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-m0"
    }
    // shortcut: debug-signed until M2, when HMC-E's signature check needs its key.
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
