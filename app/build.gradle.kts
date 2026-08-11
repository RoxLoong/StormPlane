import java.util.Properties

plugins {
    id("com.android.application")
}

// 联调凭据属于本机/构建环境配置，不能写进源码或提交到仓库。
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use(localProperties::load)
}
fun readBycwConfig(name: String): String {
    val key = "bycw.$name"
    return (project.findProperty(key)?.toString()
        ?: localProperties.getProperty(key)
        ?: "").trim()
}
fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
val bycwAppId = readBycwConfig("appId")
val bycwClientKey = readBycwConfig("clientKey")

android {
    namespace = "com.hurteng.stormplane.myplane"
    // 与自研 SDK AAR（compileSdk 36）对齐，宿主才能引用。
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hurteng.stormplane"
        // 自研 SDK 要求 minSdk 21，原工程为 16，需提升。
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BYCW_APP_ID", buildConfigString(bycwAppId))
        buildConfigField("String", "BYCW_CLIENT_KEY", buildConfigString(bycwClientKey))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // 联调演示包关闭混淆，避免 SDK 反射/注解被裁剪。
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // 通过 AAR 集成自研 SDK，与甲方接入方式一致。
    implementation(files("libs/sdk-release.aar"))
    // 中文实名自动化输入（UiAutomation ACTION_SET_TEXT 绕过 IME 限制）。
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
