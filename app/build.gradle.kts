// 依赖版本严格锁死在 PRD 第 2 节，禁止自行替换。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

// debug 预填值来自根目录 local.properties（已在 .gitignore）。
// 注意：Gradle 不会把 local.properties 自动注入 project properties，必须显式读取文件。
fun localProperty(key: String, default: String = ""): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return default
    val props = Properties()
    file.inputStream().use { input -> props.load(input) }
    return props.getProperty(key)?.takeIf { it.isNotBlank() } ?: default
}

/**
 * 是否把 API Key 预填进 APK。
 *
 * ## 为什么要这个开关
 * `buildConfigField` 注入的字符串会**明文落在 DEX 里**，任何人下载 APK
 * 都能用 `strings classes*.dex | grep sk-` 提取出来。v0.1.0~v0.1.17
 * 的公开 Release 就是这么把真实 Key 泄露出去的。
 *
 * 因此：
 * - **本地开发**（默认 true）：从 local.properties 读，省去每次手填。
 * - **CI / 公开分发**（`-PprefillKeys=false`）：注入占位符，
 *   用户首次启动后自己在设置页填。
 *
 * 绝不能因为「反正仓库里没有 local.properties」就以为安全——
 * CI 会在构建前**生成**一个带 Secrets 的 local.properties。
 */
val prefillKeys = (project.findProperty("prefillKeys") as String?)?.toBoolean() ?: true

/** 公开分发时注入的占位符。用户看到它就知道要自己去设置里填。 */
val KEY_PLACEHOLDER = "REPLACE_IN_SETTINGS"

/**
 * 发布签名配置来源：根目录 `keystore.properties`（已在 .gitignore）。
 *
 * ## 为什么必须有这个
 * 每个 APK 都要有数字签名，Android 用它识别「这个 app 是谁写的」，
 * 并且**拒绝安装签名不一致的更新**——这是防替换的安全机制。
 *
 * 之前 CI 出的是 `assembleDebug`，用的是 runner 上自动生成的
 * `~/.android/debug.keystore`。GitHub runner 是一次性 VM，
 * keystore 用完即弃，**每次构建出来的包签名都不一样**。
 * 结果就是：每个版本都只能卸载重装，手机里的数据一起没。
 * 这不是「装不上」，是**永远装不上更新**。
 *
 * 现在改成一个固定的发布密钥：本地与 CI 共用同一份 `keystore.properties`，
 * CI 从 Secrets 里把 keystore 还原出来再用。
 * 从此 `adb install -r` 一直有效。
 *
 * 文件缺失时不报错，只是不给 release 配签名——
 * 这样 clone 仓库的人 `assembleDebug` 照样能跑，不会被别人的密钥卡住。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.mistakebook"
    compileSdk = 35

    defaultConfig {
        // -PappIdSuffix=.probe 可构建一个 applicationId 不同的探针包，
        // 用于和 CI 正式签名包共存着调试（签名不一致时无法 -r 覆盖安装），不污染正式包的数据。
        val appIdSuffix = (project.findProperty("appIdSuffix") as String?) ?: ""
        applicationId = "com.mistakebook$appIdSuffix"
        minSdk = 26
        targetSdk = 35
        // versionCode 必须单调递增：1 = v0.0.1, 2 = v0.0.2, 3 = v0.0.3, 4 = v0.0.4, 5 = v0.0.5, 6 = v0.0.6, 7 = v0.0.7, 8 = v0.0.8, 9 = v0.0.9, 10 = v0.0.10, 11 = v0.0.11, 12 = v0.0.12, 13 = v0.0.13, 14 = v0.0.14, 15 = v0.0.15, 16 = v0.0.16, 17 = v0.0.17, 18 = v0.0.18, 19 = v0.0.19, 20 = v0.0.20, 21 = v0.0.21, 22 = v0.0.22, 23 = v0.0.23, 24 = v0.0.24, 25 = v0.0.25, 26 = v0.0.26, 27 = v0.0.27, 28 = v0.0.28, 29 = v0.0.29, 30 = v0.0.30, 31 = v0.0.31, 32 = v0.0.32, 33 = v0.0.33, 34 = v0.0.34, 35 = v0.0.35, 36 = v0.0.36, 37 = v0.0.37
        versionCode = 37
        versionName = "0.0.37"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 固定 debug 签名证书：杜绝 CI 每次运行重新生成随机 debug.keystore 导致覆盖安装报「签名不一致」
        val stableDebugKey = rootProject.file("signing/debug.keystore")
        if (stableDebugKey.exists()) {
            getByName("debug") {
                storeFile = stableDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 只有开发者自己的本地构建才注入真实 Key（prefillKeys 默认 true）。
            // CI 与公开分发传 -PprefillKeys=false，注入占位符，APK 里不含任何密钥。
            if (prefillKeys) {
                buildConfigField("String", "MINERU_API_KEY", "\"${localProperty("MINERU_API_KEY")}\"")
                buildConfigField("String", "LLM_API_KEY", "\"${localProperty("LLM_API_KEY")}\"")
            } else {
                buildConfigField("String", "MINERU_API_KEY", "\"$KEY_PLACEHOLDER\"")
                buildConfigField("String", "LLM_API_KEY", "\"$KEY_PLACEHOLDER\"")
            }
            // Base URL 与模型名不是机密，公开包也带上，减少用户首次配置量
            buildConfigField(
                "String",
                "LLM_BASE_URL",
                "\"${localProperty("LLM_BASE_URL", "https://api.deepseek.com")}\""
            )
            buildConfigField(
                "String",
                "LLM_MODEL",
                "\"${localProperty("LLM_MODEL", "deepseek-chat")}\""
            )
        }
        release {
            // 用上面那个固定密钥签名；没配置 keystore.properties 时保持未签名，
            // 这样 clone 仓库的人不会因为拿不到密钥而构建失败。
            signingConfigs.findByName("release")?.let { signingConfig = it }

            // **仅用于本地排障**：`-PenforceDebuggable=true` 会让 release 包
            // 也带上 android:debuggable，从而能用 `run-as` 读数据库、
            // 把设备上的真实数据拉下来比对。
            //
            // 之所以做成开关而不是直接改：debuggable 的包能被任意工具附加调试，
            // 一旦手滑流进正式发布就是安全事故。默认永远是关的，
            // CI 也不传这个参数（scripts/CI 里可 grep 确认）。
            if ((project.findProperty("enforceDebuggable") as String?) == "true") {
                isDebuggable = true
            }

            // 正式打包的 APK 一律不含密钥，使用者必须自行填写，
            // 即使 local.properties 有值也不注入。
            // 空串会被 BuildConfigKeys.isPlaceholder() 判成「未配置」，
            // 不需要占位符——公开包里连这个字符串都不该出现。
            buildConfigField("String", "MINERU_API_KEY", "\"\"")
            buildConfigField("String", "LLM_API_KEY", "\"\"")
            buildConfigField("String", "LLM_BASE_URL", "\"https://api.deepseek.com\"")
            buildConfigField("String", "LLM_MODEL", "\"deepseek-chat\"")

            // **R8 暂时关闭。**
            //
            // 混淆会剥掉 kotlinx.serialization / Retrofit 依赖的反射信息，
            // 序列化在运行时会静默失败（编译期完全正常，运行时才炸），
            // 而这种问题只有真机能发现。proguard-rules.pro 里已经有对应 keep 规则，
            // 但**尚未在真机验证过**。
            //
            // 当前用户的手机只承受得起一次卸载（数据靠 App 内备份恢复，
            // 而 API Key 已失效、无法重新识别题目）。万一混淆出问题，
            // 修复还需要再卸载一次，代价太大。
            // 所以先关掉，拿到「稳定签名 + debuggable=false」两个确定收益；
            // 真机验证过之后再打开。
            isMinifyEnabled = false
            isShrinkResources = false
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
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

ksp {
    // Room schema 导出到 app/schemas，方便后续 migration 校验
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ===== Kotlin / 基础 =====
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ===== Compose（BOM 2024.10.01）=====
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // ===== Room =====
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ===== DataStore / 安全存储 =====
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ===== 网络 =====
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ===== CameraX =====
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ===== 图片加载 =====
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ===== 后台提醒 =====
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ===== 测试 =====
    testImplementation("junit:junit:4.13.2")
    // Paparazzi：JVM 上无设备渲染 Compose UI 出 PNG（README 截图的来源）
    testImplementation("app.cash.paparazzi:paparazzi:1.3.5")
    // mockk：给截图测试提供 relaxed mock 的 ViewModel（组件只把它当回调入口）
    testImplementation("io.mockk:mockk:1.13.13")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// MathHtmlContractTest 直接读 assets/katex/math.html 做契约断言（防 render 被改回
// async 导致 evaluateJavascript 拿不到返回值）。Gradle 默认不跟踪测试进程自读的
// 文件，不声明输入的话改 math.html 后测试不会重跑——v0.0.31 事故就会复现。
tasks.withType<Test>().configureEach {
    inputs.file("src/main/assets/katex/math.html")
}
