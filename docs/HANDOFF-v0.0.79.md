# 交接：Ascensalis（错题本 Android App）— 当前状态 v0.0.79

> 这份文档写给**接手本项目的下一个 AI / 开发者**。自包含，不依赖任何会话历史。
> 读完这一份 + `C:\Users\26315\application\错题本-Goal-提示词-v0.0.79.md` 即可无缝开工。

---

## 1. 一句话现状

**v0.0.79 已发布，main 与最新 tag 的 CI 全绿（1234 个单测），Release 带有官方签名的 `mistakebook-v0.0.79-android.apk`。**
当前基线：versionCode **79** / versionName **0.0.79**。

## 2. 本版本（v0.0.79）完成要点

1. **KaTeX 公式位图缓存色相隔离**（`app/src/main/java/com/mistakebook/math/MathRenderer.kt`）：
   - 将公式位图缓存键升级为携带前景色维度（`$colorCss:$key`），深浅模式两套位图独立共存于 LRU 缓存中；
   - 切换深色/浅色模式时**不再全量清空 96MB 缓存**，切回原主题时直接命中，彻底解决切换主题后翻阅长错题本或历史对话时的全量重新排版光栅化与滑动掉帧；
   - 补充 `MathCacheKeyTest.kt`，验证前景色命名空间隔离特性；
   - `PdfExporter` 在生成 A4 试卷时显式指定深色墨水前景色（`MathRenderer.FG_CSS_LIGHT`），杜绝深色模式下将白色公式打印到白纸上的问题。

2. **WordbookDatabase 开启 Schema 导出与契约防线**（`app/src/main/java/com/mistakebook/wordbook/data/WordbookDatabase.kt`）：
   - 开启 `exportSchema = true`，规范版本常量化 `WORDBOOK_DB_VERSION = 2`；
   - 新增 `WordbookMigrationRegistrationTest.kt` 契约测试，自动化守卫迁移定义与注册的一致性、连续性，并防范升级时静默清空学习进度（禁止 `fallbackToDestructiveMigration`）。

## 3. 下一步任务（Phase 8，目标 v0.0.80）

接下来重点推进 **巨型上帝文件组件化解耦（架构瘦身）**：
1. **`PomodoroScreen.kt`（2403 行）拆分**：按职责拆分表盘控制（`PomodoroTimerTab.kt`）、学科与任务面板（`PomodoroTaskBoard.kt`）、多维统计看板（`PomodoroStatsTab.kt`），纯组件化重构，零改动业务逻辑。
2. **`SettingsSections.kt`（2606 行）拆分**：按 AI/OCR、外观主题、常规与备份三类拆分成子组件。
3. **`WordbookViewModel.kt`（1552 行）解耦**：抽取词库检索防抖代理与 TTS 朗读代理。

## 4. 环境与发版铁律

- 活跃路径：`C:\Users\26315\application\work\ascensalis-opt`（不要动同级 `ascensalis` 旧目录）。
- 凭据提取：Windows 凭据管理器 `git credential fill`。
- 推送命令：`cmd /c scripts\push-retry.cmd`。
- Tag 发布命令：`powershell -ExecutionPolicy Bypass -File scripts\tag-retry.ps1 -Tag "v0.0.80"`。
