# Ascensalis 交接上下文（v0.0.86 已发布，CI 绿，仓库已整理）

> 写给下一个接手的 agent。读完这一份即可继续工作，不需要回看历史会话。
> 更新时间：2026-11-01。v0.0.86 两项反馈已交付并发布（CI 绿，签名 APK 已挂 Release）；同日完成仓库整理：Release/Tag 精简到 50 个并补全中文标题、分支收敛到只剩 main、`.gitignore` 编码修复、文档合并。

---

## 1. 项目概况

- **App**：Ascensalis（错题本 Android 应用），Kotlin + Jetpack Compose (Material3) + Room + DataStore + Retrofit/OkHttp + kotlinx.serialization + CameraX + WebView(KaTeX) + 系统 PdfDocument
- **GitHub**：`ausyeah/ascensalis`
- **唯一工作副本**：`C:/Users/26315/application/work/ascensalis-opt`（当前 HEAD `d884474`，工作树干净；tag `v0.0.86` 已推）
- ✅ **旧副本 `../ascensalis` 已于 2026-11-01 删除**（内容 100% 已进 main 且 main 更新；备份 `ascensalis-old-copy-backup.tar.gz`）。现在 `../` 下只有 `ascensalis-opt` 一个仓库副本
- main 直推是本仓库既有节奏；**不开 PR**。发 tag 会触发 CI 构建并附签名 APK 到 Release（用户嫌脚本麻烦时，agent 可以自己打 tag：`git tag -a vX.Y.Z -m "错题本 vX.Y.Z" && git push origin vX.Y.Z`，再用 API PATCH 中文标题/说明）
- ⚠️ **本仓库不许再配 alternates**。历史上 `ascensalis-opt` 曾把 `../ascensalis/.git/objects` 写成 alternates，删掉旧副本会让 64 个 tag 和 main 历史的对象全部失效（2026-11-01 踩过一次，靠从 GitHub 重新克隆补回）。仓库必须自包含：`git fsck` 不该有 `invalid sha1 pointer`

### 版本号约定
`app/build.gradle.kts` 里 versionCode/versionName 必须同步递增（当前 **86 / "0.0.86"**，第 96-98 行附近，上面有一大串注释记录每个版本）。版本提交里**只准改这两行**。

## 2. 硬性规则（用户 v0.0.85 简报原话精神的延续，每版都适用）

1. 只在 ascensalis-opt 改动（旧副本已删，别再找）
2. 本机**没有 Android SDK**，不要尝试跑构建/单测——CI（GitHub Actions）会跑 `testDebugUnitTest` + 出签名 APK，本地靠静态自查
3. 界面与提示全中文，**所有文案进 `app/src/main/res/values/strings.xml`**（该文件是 CRLF，读写时注意换行符）
4. 注释写「为什么」，不写「是什么」
5. **每个行为修复必须配 JVM 单元测试**，没有测试的修复不算完成
6. 中文提交信息，前缀 `fix:` / `refactor:` / `test:` / `chore:` / `feat:`；每个任务一个 commit
7. **禁止引入任何新依赖**（PRD 之外的库一个都不加，如 docx/pdf 解析都是零依赖手写）
8. 不新增 TODO/FIXME，不留临时方案
9. 改 `PosterContent`（@Serializable，随 WorkData 持久化）时，**新字段必须带默认值**（`val x: String = ""`），否则老任务反序列化崩
10. 有 contract test（Version8xFeaturesContractTest 等）会 `readSource` 生产代码断言包含字面量——改了被钉住的字面量要同步改测试，验收方式见 §6

## 3. 已发布版本（全部 CI 绿、附签名 APK、中文发布说明）

Releases 现存 **50 个**（v0.0.12 ~ v0.0.86），每个都有中文标题与说明，逐版本要点见 [`docs/CHANGELOG.md`](CHANGELOG.md)。v0.0.11 及更早的版本只有安装指引、无版本特性记录，已连同 tag 一并删除。

| 版本 | 要点 | Release |
|---|---|---|
| v0.0.83 | 思考折叠修复（生成中不可折叠；答案开始输出即自动收起；仅空闲时可点击开合，`ThinkingBlock(locked=)`） | releases/tag/v0.0.83 |
| v0.0.84 | 八项反馈：OCR 测试成功信息换行 / HTML 首屏显示不全（heightDp 不按 html keyed，stale delegate 修复）/ 主页搜索去掉灰 placeholder / 思考折叠简化定稿 / 成就墙 38 个新徽章图（Magpie 生成，灰黑白未解锁）/ 资料库官方文件类型图标 / 渲染测试提示词 `docs/RENDER-TEST-PROMPT.md` / 背单词每日新词 50/100/200/自定义 / 番茄钟计时 UI 优化 / 左右滑卡一半修复 | releases/tag/v0.0.84 |
| v0.0.85 | T1 全量文案外移（255 处，strings.xml 673→886 keys）/ T2 HtmlSanitizer 未闭合危险标签漏网 / T3 HtmlBlock 四拍重测给取消路径 / T4 DocumentTextExtractor XXE 加固+16MB 上限 / T5 横滑让道判定纯函数化+兜底回收 / T6 HtmlBlock 非流式高度允许收缩+ChatGenerationService 渠道名文案 | **https://github.com/ausyeah/ascensalis/releases/tag/v0.0.85** |
| v0.0.86 | 两项用户反馈：① 背单词「每日新词上限」乱了——读取侧把旧版「0=不限」收敛进 1..500（`normalizeDailyNewLimit`，低于下限回落默认档 20 而非钳成 1）+ 设置页标签独占一行、档位改 FlowRow 换行；② 成就墙重排版——去掉 8 页翻页改成整幅滚动自适应网格 + 顶部战绩卡（完成度进度条）+ 每枚章露出名字与未解锁进度，排序改「已解锁在前、未解锁在后，两组都按难度升序」（`BadgeEngine.sortForWall` 纯函数） | **https://github.com/ausyeah/ascensalis/releases/tag/v0.0.86** |

## 4. 关键文件地图（近期改动集中地）

- `app/src/main/java/com/mistakebook/ui/chat/ChatScreen.kt` — ThinkingBlock（streaming/locked 参数）、~8 处文案外移
- `app/src/main/java/com/mistakebook/ui/common/HtmlBlock.kt` — 高度自适应核心。**记住三个坑**：① `heightDp` 绝不能按 html keyed（factory 只跑一次，闭包捕获旧委托 → 永远 60dp）；② `streaming` 参数经 `var streamingNow by remember` + `SideEffect` 中转进闭包；③ 四拍重测（150/400/1000/2000ms）用 `LaunchedEffect(html, webView)` 调度，自动取消
- `app/src/main/java/com/mistakebook/markdown/HtmlSanitizer.kt` — 危险标签整段删的正则是可选闭合形态：`Regex("<script\\b[^>]*>(?:[\\s\\S]*?</script>)?", IGNORE_CASE)`（script/iframe/object/form/button 五个）
- `app/src/main/java/com/mistakebook/data/library/DocumentTextExtractor.kt` — 零依赖 docx/xlsx/pdf 提取；`internal MAX_INPUT_BYTES = 16MB`；两处 DocumentBuilderFactory 都 `disallow-doctype-decl` + `setExpandEntityReferences(false)`
- `app/src/main/java/com/mistakebook/ui/home/HomeScreen.kt` — 卡片横滑：`internal fun shouldYieldToPager(accX, accY, elapsedMs, touchSlop)` 纯函数（纵向优先、未越 slop 保守让道、>400ms 一律认领、速度阈值 slop/90）+ `yielded && !claimed && abs(accX) > revealWidthPx*1.5f` 的兜底回收。单测在 `app/src/test/java/com/mistakebook/ui/home/HomeScreenYieldTest.kt`（7 例）
- `app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt`、`app/src/main/java/com/mistakebook/pomodoro/PomodoroScreen.kt` — Pager 同步写回防打架：`if (!pagerState.isScrollInProgress && pagerState.targetPage != ...) animateScrollToPage(...)`；海报文案在 onClick 协程外 hoist `val posterStatsHeader = stringResource(...)`
- `app/src/main/java/com/mistakebook/data/prefs/SettingsStore.kt` — `setWordbookDailyNewLimit` 与 `snapshot()` 读写两侧都走 `MIN/MAX/DEFAULT_DAILY_NEW_LIMIT` + `normalizeDailyNewLimit`；`snapshotNow()` 与 `snapshot()` 同一条构造路径，收敛对所有调用方生效
- `app/src/main/java/com/mistakebook/data/prefs/SettingsSnapshot.kt` — 文件末尾的 `MIN/MAX/DEFAULT_DAILY_NEW_LIMIT` + `normalizeDailyNewLimit(stored: Int?)`：读取侧把旧版「0=不限」收敛进 1..500，**低于下限回落默认档 20 而不是 coerceIn 钳成 1**（钳成 1 = 把老用户掐到一天一个新词，v0.0.86 CI 红过一轮才改对）
- `app/src/main/java/com/mistakebook/ui/settings/SettingsSections.kt` — 每日新词 chips 50/100/200/自定义（**标签独占一行 + FlowRow 换行**，别再塞回同一行）；CustomDailyLimitDialog 区间用同一批常量；OCR 测试结果 banner 全宽显示（换行问题已修）
- `app/src/main/java/com/mistakebook/domain/BadgeEngine.kt`（48 徽章 7 类；`Badge.difficulty` 难度键 + `sortForWall(badges, unlockedKeys)`：已解锁在前、两组内难度升序、同难度按 ordinal）+ `BadgeWallScreen.kt`（**v0.0.86 重排版**：LazyVerticalGrid 自适应网格 + 顶部战绩卡 + 已解锁/未解锁分区头；BADGE_IMAGES 全量 48 映射；翻页已删）+ `res/drawable-nodpi/badge_*.png`（48 张）
- `app/src/main/java/com/mistakebook/ui/library/LibraryScreen.kt` — 文件图标映射 pdf/doc/xls+csv/md/txt/json/图片/音/视/zip
- `app/src/main/java/com/mistakebook/poster/PosterContent.kt` — 有 `statsHeader: String = ""`；footerText 默认 `""`；builders 带 statsHeader/footerText 参数
- `app/src/main/java/com/mistakebook/pipeline/RecognitionSubmitter.kt` / `RecognitionEngine.kt` — 有 `appContext: Context` 参数，阶段文案走 appContext.getString
- `app/src/main/java/com/mistakebook/pomodoro/PomodoroViewModel.kt` — 拿 Application 要用 `getApplication<android.app.Application>()`（ctor 参数 `app` **没有** `val`，不是属性，直接 `app.getString` 编译不过）
- `docs/RENDER-TEST-PROMPT.md` — 18 项渲染测试提示词 + 验收表（v0.0.84 第 5 项交付物）

## 5. 环境怪癖（踩过的坑，别再踩）

- 本机 shell 是 Git Bash；**grep 实为 ugrep**（部分 GNU 参数不兼容）；bash **heredoc 会吃一层反斜杠**——写脚本一律用 Write/Edit 工具落盘，不要 heredoc
- `strings.xml` 是 CRLF；python 脚本做替换时 `\n` 要自适应成 `\r\n`（先探测文件含不含 `\r\n`）
- **GitHub token**：Windows 用户级环境变量 `GH_TOKEN`（ghp_ 开头 40 字符）。取法：`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('GH_TOKEN','User')"`。旧的 fine-grained `github_pat` 已 401 失效，别用。**绝不把 token 硬编码进任何文件**
- 推送：`scripts\sync.ps1` 无代理配置，代理只对 `127.0.0.1:7890` 间歇可用（`scripts/tag-retry.ps1`、`scripts/push-retry.cmd` 走代理）；**代理全挂时直接 `git push` 反而能成功**——curl 探测显示 000 具有误导性，别只看探测结果
- CI 查询：`gh run list` / `gh api repos/ausyeah/ascensalis/actions/runs`；release 修补：`gh api -X PATCH repos/ausyeah/ascensalis/releases/<id> -f name=... -f body=...`（body 是中文多行时建议写临时 json 文件再 `-d @file`）。**注意：GitHub API 返回的 JSON 里 shell 变量会带上 Windows 的 `\r`，`[ "$CONC" = "success" ]` 这类比较要用 `tr -d '\r'` 去掉，否则会误判 CI 失败**
- 沙箱环境下载/解压的外来文件放独立空目录，python 用 `-I` 跑

## 6. Contract test 验收方法（改字面量后必做）

部分测试（如 `Version80FeaturesContractTest`）用 `readSource("app/src/main/java/...")` 读生产源码并断言 `.contains("中文字面量")`。改文案外移后这些字面量会从源码里消失导致测试红。验收脚本思路：扫所有测试文件里的 `readSource` 目标 + `contains("含汉字")` 字面量，逐一确认该字面量仍存在于目标源码**或** strings.xml。上次用的脚本在 `C:\Users\26315\AppData\Local\Temp\scan_contracts.py`（temp 可能已被清，思路照抄重写即可）。v0.0.85 因此同步改过 3 处：Version80FeaturesContractTest（`R.string.settings_group_ocr`）、Version81FeaturesContractTest（`R.string.chat_cd_copy_thinking`）、GradingContractTest（`R.string.chat_grading_default_message`）。

## 7. Compose 高频编译/运行时坑（CI 上翻过车的）

1. `awaitEachGesture` 的 `AwaitPointerEventScope` 是受限作用域：**不能直接调任意挂起函数**（`snapTo` 要 `scope.launch { }` 包一层，settle 判断用局部变量累加）
2. `stringResource` 只能在组合期调用；onClick 协程里要用就必须在外层 hoist 成 `val`
3. `AndroidView` factory 只跑一次：闭包里捕获的参数是首帧值。要传新值进闭包，用 `var x by remember` + `SideEffect { x = param }` 中转，或放 `update` 块
4. 主 pointer pass 子节点先收事件 → 卡片内 draggable 会抢 Pager 手势；pager 双向同步要加 `isScrollInProgress`/`targetPage` 守卫
5. CI 报错定位：错误行号 + "e: " 前缀直接 grep 源文件；改完只推相关 commit，别重置历史（除非像上次那样 `git reset --soft HEAD~1` 拆版本提交，此时用 `--force-with-lease`）

## 8. 常规工作流（下一批反馈来了照这个走）

1. 逐条确认需求 → 改代码（每个任务一个 commit，中文 fix:/refactor:/test: 前缀）
2. 本地静态自查：grep 检查硬编码中文（`git diff` 里新增字符串字面量含汉字且不在 strings.xml = 违规）；确认无 TODO/FIXME、无新依赖
3. 改 `app/build.gradle.kts` versionCode/versionName（独立 commit，只动两行）
4. 推送（先试 `scripts\sync.ps1`，失败走 push-retry.cmd，再失败直接 git push）
5. 盯 CI 到绿（跑 testDebugUnitTest + 签名 APK）；红了按 §7 修，commit 再推
6. 用户要出包时：给 tag（agent 可自己打 `git tag -a` + `git push origin`，或引导用户跑 `scripts\release.ps1`）；tag 构建自动建 Release 附 APK。再用 API PATCH 中文标题/说明（body 写临时 json 文件再 `--data-binary @file`，注意中文 UTF-8）
7. 回复用户必须附**逐条自检清单**（不许省略）

## 9. 待办 / 未决

- **无未完成任务**。v0.0.86 已交付、已打 tag 发布（tag `v0.0.86` → CI run 41282597332 全绿），Release 已挂签名 APK + 中文说明：**https://github.com/ausyeah/ascensalis/releases/tag/v0.0.86**
- 等用户真机验证 v0.0.86 的两项修复（背单词上限档位显示、成就墙重排版 + 排序）
- v0.0.86 的行为修复都配了 JVM 单测：`DailyNewLimitNormalizeTest`（5 例）、`BadgeEngineTest` 新增 4 例（难度键 / 已解锁优先 / 同难度稳定 / 全未解锁纯难度序）
- 已知但有意保留的行为：老 .xls（OLE2）零依赖解不开，返回空串由调用方提示「无法提取」；扫描版 PDF 提不出文字同理
- `PdfImporter` 目前**没有单元测试**：jules 分支上有一份，但它的构造签名停留在三代之前（`SettingsStore` 现在走 DataStore、`MineruClient` 多了 `UsageStatsStore`、`RecognitionEngine` 多了 `files`/`appContext`），纯 JVM 下跑不起来（要 Robolectric）。要补测试得先决定是否引入 Robolectric —— 那是**新增依赖**，违反 §2 第 7 条，需用户拍板
