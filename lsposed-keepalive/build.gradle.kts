plugins {
    id("com.android.application") version "8.13.2"
}

android {
    namespace = "com.moshangssh.bandhealth.keepalive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moshangssh.bandhealth.keepalive"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // 模块是本机自用，debug 签名即可直接装；不开混淆，LSPosed 才能读到入口类。
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
