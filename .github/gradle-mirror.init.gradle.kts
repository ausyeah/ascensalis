// CI 专用 Gradle 镜像（v0.0.140）。
//
// 背景：2026-11-08 GitHub Releases 分发持续 504 —— Gradle 发行包
// (gradle-distributions) 与 Maven Central 里 JetBrains 的
// kotlin-compiler-embeddable 都会 302 到 github.com/.../releases/download/，
// 两处同时打不通，CI 在依赖解析阶段直接红。
//
// 第一版尝试过阿里云，失败：GitHub Actions 跑在海外，访问阿里云超时，
// 连 KSP 的 plugin marker 都「找不到」。改用 **Google 的 Maven Central
// 镜像**（GCS，全球可用、直链、不经过 GitHub 二次跳转）：
//   https://maven-central.storage-download.googleapis.com/maven2/
// 实测该镜像上有 KSP marker 与 kotlin-compiler-embeddable。
//
// 只在 CI 里通过 -I 加载（本地不受影响：本地已有缓存）。做法：把镜像
// **插到仓库列表最前**，命中即走镜像；原有官方源整体保留在后面兜底
// （含 google() 的 content filter 原样保留）。
//
// 注意：settings.gradle.kts 用了 RepositoriesMode.FAIL_ON_PROJECT_REPOS，
// 所以不能往 allprojects 里塞仓库，只能改 settings 的两处 RepositoryHandler。
import org.gradle.api.artifacts.dsl.RepositoryHandler

fun RepositoryHandler.prependCentralMirror() {
    val existing = toList()
    clear()
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    existing.forEach { add(it) }
}

settingsEvaluated {
    pluginManagement {
        repositories.prependCentralMirror()
    }
    dependencyResolutionManagement {
        repositories.prependCentralMirror()
    }
}