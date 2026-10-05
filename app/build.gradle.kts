import java.util.Properties

// 应用自更新：版本号跟着 git 提交数走——每推一次 main 自动 +1；
// 本机与 CI 对同一提交算出同一个号，手机端比较 versionCode 即可判断有无新版本
val commitCount: Int = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tingsiwei.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tingsiwei.app"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "1.5.$commitCount"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // 签名密码只留在本机 keystore.properties（已 gitignore）；CI 由 Actions Secrets 还原同名文件。
    // 本机与 CI 用同一把 jks → 所有包的签名指纹一致，覆盖安装不需要卸载
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasKeystore = rootProject.file("tingsiwei-release.jks").exists() &&
        keystoreProps.getProperty("storePassword", "").isNotBlank()

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("tingsiwei-release.jks")
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "tingsiwei")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug 也锁同一把钥匙：手机上装的调试包能被 CI 的正式包直接覆盖升级，不报签名冲突
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
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
        compose = true
        // 应用自更新需要编译期写入 BuildConfig.VERSION_CODE/VERSION_NAME 与远端比较
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // sherpa-onnx 离线语音识别
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
