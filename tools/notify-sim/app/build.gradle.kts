plugins {
    id("com.android.application")
}

android {
    namespace = "com.terry.notifysim"
    compileSdk = 35

    defaultConfig {
        minSdk = 28                        // 要 Person / MessagingStyle
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // 两个变体，只差包名：
    //   fakewx —— 伪装成微信，用来验证"该建的对不对"
    //   other  —— 普通包名，用来验证"白名单是不是真的拦得住"
    // 之所以要真的改包名而不是加个开关：App 判断的是 StatusBarNotification.packageName，
    // 那个值由系统按安装包填，改不了。
    flavorDimensions += "pkg"
    productFlavors {
        create("fakewx") {
            dimension = "pkg"
            applicationId = "com.tencent.mm"
        }
        create("other") {
            dimension = "pkg"
            applicationId = "com.example.notifysim"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}
