import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// 签名凭据从仓库外的 keystore.properties（或同名环境变量）读取，绝不写入版本库。
// 缺失时 release 仍然能构建（产物未签名），方便他人克隆后直接编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    // 必须用 Reader 显式指定 UTF-8：Properties.load(InputStream) 按 ISO-8859-1
    // 解码，会让含中文的路径变成乱码（例如 /root/心情日记/... 变成 /root/å¿æè®°/
    if (keystorePropsFile.exists()) {
        keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}
fun signingValue(key: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("MOODDIARY_STORE_FILE")
val releaseStorePassword = signingValue("MOODDIARY_STORE_PASSWORD")
val releaseKeyAlias = signingValue("MOODDIARY_KEY_ALIAS")
val releaseKeyPassword = signingValue("MOODDIARY_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null }

android {
    namespace = "com.mooddiary.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mooddiary.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "2.4.0"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("警告：未找到 keystore.properties 或 MOODDIARY_* 环境变量，release 产物将不签名。")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// 导出 Room schema，使数据库迁移可被测试与审查
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 单元测试：数据层用内存实现，纯 JVM 运行，无需真机或 Android 运行时
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
