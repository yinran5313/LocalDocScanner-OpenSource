plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.localdoc.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localdoc.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "4.4.2-rc1"

        // 当前交付目标为近年的小米手机。OCR/OpenCV 原生库仅保留 ARM64，
        // 避免把 x86 模拟器和旧 32 位手机的三套二进制重复塞进安装包。
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":office-engine"))
    implementation(project(":scan-native"))
    implementation(project(":ppocr-sdk"))
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // 协程 / ListenableFuture 互操作（CameraX 绑定要用）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")

    // 读取相册图片的方向信息
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 条码 / 二维码（纯 Java，无 so）
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.dhatim:fastexcel:0.18.4")
    implementation("org.apache.commons:commons-csv:1.10.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.86")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // 相机
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // 本地存储
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // 图片加载（缩略图）
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
