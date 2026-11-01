plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.exception.gwangju_safe_walk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exception.gwangju_safe_walk"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("com.naver.maps:map-sdk:3.22.0")
    // 네이버 지도의 FusedLocationSource 가 내부에서 쓰는 위치 라이브러리
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}