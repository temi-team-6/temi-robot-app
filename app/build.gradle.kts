import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서버 주소와 기기 토큰은 저장소에 넣지 않는다. secrets.properties(README 참고)에서 읽는다.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "kr.hylink.temirobot"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.hylink.temirobot"
        // Temi 런처 16405(안드로이드 6.0.1, API 23). SDK는 0.10.81이 이 런처에서 쓸 수 있는 마지막 버전이다.
        minSdk = 23
        targetSdk = 23
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", "\"${secrets.getProperty("server.url", "wss://temi.hylink.kr/ws/device")}\"")
        buildConfigField("String", "TEMI_TOKEN", "\"${secrets.getProperty("temi.token", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk를 Temi 기기(23)에 맞춰 낮게 둔다. 스토어 배포 앱이 아니다.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("com.robotemi:sdk:0.10.81")
    // SDK 리소스가 Theme.AppCompat을 참조한다.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
