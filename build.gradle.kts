// 顶层构建文件：插件版本统一在此声明，各模块只 apply 不写版本。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    // Paparazzi：JVM 上无设备渲染 Compose UI 出 PNG（README 截图的来源）
    id("app.cash.paparazzi") version "1.3.5" apply false
}
