plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videosync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videosync"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // даёт lifecycleScope для запуска корутин из Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Фоновая работа + очередь с повторами и условием "есть сеть"
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Сеть (загрузка файлов на сервер)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Media3 Transformer — сжатие/перекодирование видео в 480p
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
}
