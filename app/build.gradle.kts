plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.tg2oto.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tg2oto.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 用 debug keystore 签名，保证 release 包能直接装上测试；
            // 不开启 R8，避免它把 OrtEngine 这类被 Python 以字符串反射查找的类裁掉。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    androidResources {
        // onnx 已压缩意义不大，且不压缩后可以直接 openFd 探测大小、复制更快
        noCompress += listOf("onnx")
    }
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // 依赖包体积大且 chaquo.com 下载极慢，改用本地 wheel（离线可重复构建）
            options("--no-deps")
            install("wheels/chaquopy_libcxx-180000-0-py3-none-android_24_arm64_v8a.whl")
            install("wheels/chaquopy_openblas-0.2.20-5-py3-none-android_21_arm64_v8a.whl")
            install("wheels/numpy-1.26.2-0-cp312-cp312-android_21_arm64_v8a.whl")
            install("wheels/TextGrid-1.5-py3-none-any.whl")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
