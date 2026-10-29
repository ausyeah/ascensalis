# 交接：Ascensalis（错题本 Android App）v0.0.58 反馈批次

> 这份文档是给「接手本批次的另一个 AI/开发者」的完整简报。
> 直接从头读即可；第 6 节是当前 CI 的全部编译错误与修法。

---

## 0. 项目与仓库

- 本地仓库：`C:\Users\26315\application\work\ascensalis-opt`
- 远端：<https://github.com/ausyeah/ascensalis>（分支 `main`）
- 技术栈（硬性，禁止替换）：Kotlin + Jetpack Compose + Room + Retrofit/OkHttp +
  kotlinx.serialization + CameraX + 系统 PdfDocument
- 全中文 UI 与提示；不引入 PRD 之外的依赖；密钥不得进 git（CI 从 GitHub Secrets 读）
- 当前版本：`versionCode 57` / `versionName 0.0.57`；**发版前必须改成 58 / 0.0.58**

## 1. 当前状态（最重要）

- HEAD = `52502ff`「v0.0.58 反馈批次（对话相机后端分叉 / 公式裁切 / HTML 高度 / PDF 读取等）」
- **这次 CI 是失败的**（run `40908530029`）。第 6 节列了全部编译错误和修法。
- 工作区有 1 个**未提交**文件：

  `app/src/main/java/com/mistakebook/data/chat/ChatAttachmentPreparer.kt`

  里面已经补了 `AttachmentKind.LIBRARY` 的两个 `when` 分支（约第 130 行与第 404 行）。
  这两处对应第 6 节错误 #2、#3、#6，别重复改。
- v0.0.57 已发布，Release 带签名 APK（`mistakebook-v0.0.57-android.apk`）。

## 2. 环境限制（务必先看，能省几小时）

1. **本机没有 Android SDK**，只有 JDK 17。不能本地 `assembleDebug`。
   所有验证靠 CI：`testDebugUnitTest`（会一并编译 main + unit test）。
2. CI 只在 **main 分支 / tag / PR** 上触发。改完想验证就 push main（或开 PR）。
3. **github.com:443 经常不通**。push 必须写重试循环（失败 3~6 次后通常能成）；
   `api.github.com` 一般可用（用它查 CI、发 Release）。
4. PowerShell 5.1 的坑：
   - 中文放进 `git commit -m` 会解析失败 → 先把提交信息写文件，再用 `git commit -F 文件`
   - `$var:path` 会被当成盘符 → 用 `${var}` 或字符串拼接
   - edit 工具匹配长中文注释容易失败 → 用 ASCII 锚点，或直接用 PowerShell 改行
   - 控制台输出中文会乱码，属正常现象，不影响文件内容
5. GitHub token 由用户单独提供；**结束前提醒用户吊销并重新生成**。
   不要把 token 写进任何文件。

## 3. 发布流程（用户要求：一批做完只出一个包）

1. 确认 CI 绿（main 上最新 commit）。
2. 改 `app/build.gradle.kts`：`versionCode 58` / `versionName "0.0.58"`（同步那条版本注释）。
3. `git add -A && git commit -F <信息文件>` → push main（带重试）。
4. `git tag -a v0.0.58 -m "v0.0.58"` → `git push origin v0.0.58`（带重试）。
5. tag 会触发 CI 构建签名 APK 并挂到 GitHub Release；用 API 查
   `/repos/ausyeah/ascensalis/releases/tags/v0.0.58` 确认 asset 存在。
6. 用 API `PATCH` Release body 写中文更新说明。

## 4. 用户本轮 11 条反馈

来源：微信导出压缩包（截图在
`C:\Users\26315\AppData\Local\Temp\opencode\chatlog\shot01..shot10.jpg`）
原始文本：同目录 `chat.txt`。

| # | 反馈 | 状态与改动位置 |
|---|---|---|
| 1 | 连拍「完成」与右下角重叠（shot01） | 已改：`ui/capture/CaptureScreen.kt` 把批次卡从「浮在预览上的 BottomEnd」移进底部 Column 第一行（右对齐），不再用固定 padding 猜位置 |
| 2 | 部分公式右边被裁切（shot02） | 已改：`assets/katex/math.html` 的 `render()`/`renderBatch()` 在字号缩到 8px 仍超宽时用 `transform: scale()` 兜底；`ui/common/RichText.kt` 的 `MathImage` 再按容器宽度夹一次 |
| 3 | HTML 第一次不完整、第二次才对（shot03/04） | 已改：`ui/common/HtmlBlock.kt` 高度三时机重测（onPageFinished / 布局宽度变化 / 文档切换），只取最大值 |
| 4 | 生成过程中能打字但不能加图片 | 已改：`ui/chat/ChatScreen.kt` 去掉附件按钮的 `enabled = !streaming` |
| 5 | Tavily 查询额度不可用（shot05） | 已改：`data/chat/WebSearch.kt` 给 `searchHttpClient()` 补 `NetworkRouter.proxySelector`；`fetchUsage` 改返回 `UsageResult`（Ok/Failed），失败带真实原因（401/429/网络异常）。**编译未过，见 #7** |
| 6 | 勾选资料库后历史里看不到文件（shot06/07） | 已改：新增 `AttachmentKind.LIBRARY`；`ChatViewModel.runOnce` 用 `repository.attachLibraryFiles(...)` 登记；`ChatRepository.toOutgoing` 把 LIBRARY 从请求上下文过滤（原文已由服务注入 system） |
| 7 | 发送 PDF 不能正确调用工具读取（shot06/07） | 已改：新增 `PdfTextQuality`（乱码判定）+ `PdfPageImages`（PdfRenderer 栅格化）；`ChatAttachmentPreparer.processPdf` 抽文本不可用就转图，并把说明写进 textExcerpt 引导模型调 describe_image/ocr_image；`ChatContextAssembler.MAX_IMAGES_PER_REQUEST` 2 → 4 |
| 8 | 把场景图放到最下面（shot08，背单词页） | 已改：`wordbook/ui/WordbookScreen.kt` 把 `WordSceneImage` 的 item 移到 LazyColumn 末尾 |
| 9 | 对话上限再大；服务端自己会截断，不需要我们设上限；按 1M 输入 / 128K 输出 | 已改：`ChatCompletionStream.MAX_TOKENS` 131_072；`LlmClient.DEFAULT_MAX_TOKENS` 65_536；`ChatContextAssembler.MAX_HISTORY_CHARS` 1_000_000、`MAX_HISTORY_ROUNDS` 128 |
| 10 | 用量统计的缓存命中从来没变过（shot09） | 已改：`LlmClient` 注入 `UsageStatsStore`，对识别流水线补记账（以前只有对话记账）；设置页加口径说明「服务端不上报 prompt_cache_hit_tokens 时这一行不会变」 |
| 11 | 打印出来的 PDF 公式还是一串 LaTeX（shot10） | 已改：`print/HtmlExporter.kt` 默认 `FormulaMode` 改为 `BITMAP`（Android WebView 不支持 MathML，KaTeX 的 annotation 原文会被当文本显示） |

## 5. 一条**必须保留**的设计纠正（曾改错）

相机分两个板块，**同一套前端，后端不同**：

- **拍错题识别**：相机 → 裁剪 → 提交 MinerU/识别管线；
- **对话拍照**：相机 → **确定后直接把原图当图片附件回会话**，由模型自己调 OCR/识图工具；
  **不裁剪、不提交识别**；对话里也要支持连拍。

本批已按这个改：

- `ui/capture/CaptureScreen.kt`：CHAT 用途拍完一律入批、留在相机页（不再跳裁切）；
- `MistakeBookNavHost.kt`：新增 `submitChatBatchRaw()`，用 `CaptureBatch.drainRaw()`
  取原图 → `container.pendingChatAttachmentPaths` → `ChatScreen` 的 LaunchedEffect 转附件。

> 不要把它改回「对话也走裁剪/识别后端」。

## 6. CI 编译错误清单（照这个修，很快就绿）

来自 run `40908530029`（commit `52502ff`），按建议顺序排列：

1. **`SettingsViewModel.kt:363~372` `Unresolved reference 'UsageResult' / 'usage' / 'message'`**
   原因：`sealed interface UsageResult` 被插进了 `TavilySearch` **object 内部**（`WebSearch.kt` 第 174 行），
   而 ViewModel 按顶层类型引用。
   修法二选一：把 `UsageResult` 移到 `WebSearch.kt` **文件顶层**（推荐），
   或把引用改成 `com.mistakebook.data.chat.TavilySearch.UsageResult`。
   顺带补全 `when` 的 `is Ok / is Failed` 两个分支。
2. **`ChatAttachmentPreparer.kt:249` `No value passed for parameter 'targetFactory'`**
   原因：`PdfPageImages.render(file) { index -> ... }` 的尾随 lambda 会绑到**最后一个参数**
   （`longEdgePx: Int`）。修法：显式命名
   `PdfPageImages.render(file, targetFactory = { index -> ... })`。
3. **`ChatAttachmentPreparer.kt:277` `Const 'val' is only allowed on top level...`**
   原因：`private const val PDF_PAGE_IMAGE_NOTE` 写在了 class 体里。
   修法：移到文件顶层 / 放进 `companion object`，或改成普通 `private val`。
4. **`PdfPageImages.kt:64` `None of the following candidates is applicable`**
   核对 `PdfRenderer.Page.render` 的签名：
   `render(Bitmap destination, Rect? destClip, Matrix? transform, int renderMode)`（四个参数），
   并确认 `page.width` / `page.height` 是属性访问。
5. **`MistakeBookNavHost.kt:277` `'return' is prohibited here.`**
   原因：`onStartBatchCrop = { ... return@onStartBatchCrop ... }` 的早退在参数 lambda 里非法。
   修法：改成 if/else，不要 return：

   ```kotlin
   onStartBatchCrop = {
       if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
           submitChatBatchRaw()
           navController.popBackStack()
       } else {
           val batch = container.captureBatch
           if (batch.paths.isNotEmpty()) {
               navController.navigate(Routes.crop(batch.paths.first())) {
                   popUpTo(Routes.CAPTURE) { inclusive = true }
               }
           } else {
               submitCaptureBatch()
           }
       }
   }
   ```

6. **`ChatAttachmentPreparer.kt:119/122/389` `when must be exhaustive`**
   → 工作区**已修**（`AttachmentKind.LIBRARY` 两个分支），提交即可。

## 7. 还没做完的小事（发版前必须处理）

- **`AttachmentFileChip` 副标题**：`AttachmentKind.LIBRARY` 的 `extractedChars = 0`，
  现在会显示「已提取 0 字」。应在 `ui/chat/ChatScreen.kt` 的 `AttachmentFileChip` 里
  对 `kind == LIBRARY` 改成「资料库文件（本轮已注入上下文）」之类文案。
- 版本号 57 → 58（见第 3 节）。
- 新增单测 `PdfTextQualityTest` 已写、**未验证**；CI 会跑。
- 发版后提醒用户吊销 GitHub token。

## 8. 关键文件地图

- 对话：`ui/chat/ChatScreen.kt`（气泡/输入/附件/相机入口）、`ChatViewModel.kt`
  （发送/附件/资料库登记）、`ChatGenerationService.kt`（tool loop / extraContext）、
  `data/repos/ChatRepository.kt`（buildContext / toOutgoing / 附件）
- 附件：`data/chat/ChatAttachmentPreparer.kt`、`PdfTextQuality.kt`、`PdfPageImages.kt`、
  `AttachmentManifest.kt`
- 渲染：`markdown/MarkdownModel.kt`、`markdown/HtmlSanitizer.kt`、`markdown/SvgSanitizer.kt`、
  `ui/common/RichText.kt`、`ui/common/HtmlBlock.kt`、`ui/common/SvgBlock.kt`、
  `assets/katex/math.html`
- 相机：`ui/capture/CaptureScreen.kt`、`di/CaptureBatch.kt`、`MistakeBookNavHost.kt`、`ui/crop/*`
- 打印：`print/HtmlExporter.kt`、`print/PdfExporter.kt`
- 设置：`ui/settings/SettingsSections.kt`（Tavily / 用量）、`SettingsViewModel.kt`
- 背单词：`wordbook/ui/WordbookScreen.kt`

## 9. 验收口径

用户要求「一批做完只出一个包」。完成时自检：

- [ ] CI 绿（main 最新 commit 的 `testDebugUnitTest`）
- [ ] v0.0.58 APK 挂在 Release，版本号正确
- [ ] 第 4 节 11 条逐条对应到代码改动
- [ ] 相机那条**没有**把对话拍照又接回裁剪/识别
- [ ] 回复用户时按反馈条目逐条说明改了什么，并提醒轮换 token
