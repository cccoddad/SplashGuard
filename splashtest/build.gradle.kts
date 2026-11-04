plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 仅用于验收的「假开屏」App，不参与正式分发。
 * 提供三种界面，用来验证 SplashGuard 无障碍服务的点击判定与防误点规则。
 */
android {
    namespace = "com.splashguard.splashtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.splashguard.splashtest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
