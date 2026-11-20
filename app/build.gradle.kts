import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在项目根目录的 keystore.properties 里，不写进这个文件。
// 原因：这个文件跟着代码走，密码硬编码进去等于公开。properties 单独保管、不进版本库。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasSigning = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.terry.smartschedule"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.terry.smartschedule"
        minSdk = 26
        targetSdk = 35
        versionCode = 56
        versionName = "1.56"
    }

    signingConfigs {
        // 只有 keystore.properties 存在时才创建，否则 assembleRelease 没有签名可用
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 包也关掉 debuggable（Terry 2026-10-29 要求）：
            // 签名还是 debug 私钥、applicationId 不变，日常迭代照旧；
            // 只是系统不再把它标成"可调试应用"，adb attach / run-as 都进不去。
            // 这个项目不在 IDE 里断点调试，付这个代价没意义。
            isDebuggable = false
        }
        release {
            // 不开 R8：没有真机验证过的混淆配置等于埋雷 —— 反射、协程、Android Keystore
            // 任何一处被裁掉都是运行时崩溃，而且只在 release 包出现，最难查。
            // release 的核心收益是 debuggable=false + 自己的私有签名，这两项不依赖混淆。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
