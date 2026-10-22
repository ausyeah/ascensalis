# 架构与功能设计决策 (Architecture & Feature Decisions)

## 照片 OCR 增强功能调整为拍照页可选四档强度 (2024-10)

### 背景与痛点
原来「照片增强」是一个在设置页的 Boolean 设置 (`enhancePhotos`)，用于控制 `ImageNormalizer` 内部的灰度化和对比度增强处理。
存在的问题：
1. **隐藏过深**：属于影响拍照质量即时效果的核心控制参数，但埋在设置里，用户在拍摄当刻找不到也想不起来去调整。
2. **粒度过粗**：一刀切的开关无法适应不同光线、纸张及笔迹的情况。之前的参数映射在极暗条件下会把照片处理成“全黑”。

### 决策内容
1. **迁移开关入口至拍照页**：
   将是否开启对比度增强以及选择强度的控制放到了 `CaptureScreen` 拍照界面顶部，使用单选分段按钮 (`SingleChoiceSegmentedButtonRow`) 进行展现，使得所见即所得。选择的结果依然会即刻写入到本地配置中供其他导入流程共享。
2. **将布尔开关改造为 0-3 四档整数档位**：
   - 0 档 (关闭)：原样返回，不做增强。
   - 1 档 (轻度)：斜率 1.6，暗部底线 48。适合本身光线好、只是想稍作灰度化的照片。
   - 2 档 (标准)：斜率 4.0，暗部底线 12。为之前的原有效果（经过优化不再全黑），能够压掉大部分纸张杂色，保留主要笔迹细节。默认档位。
   - 3 档 (强力)：斜率 8.0，暗部底线 0。极端去底色，暗部直接归零（会丢失部分灰度层次），用于极暗图片或追求高反差。
3. **兼容与迁移策略 (Boolean -> Int)**：
   - 使用新键 `KEY_OCR_STRENGTH` 代替旧键。
   - 对于从老版本升级过来的用户，保留旧键 `KEY_ENHANCE_PHOTOS` 仅仅用于只读的配置合并迁移，当用户旧配置为 `false` 则映射成 0 (关闭)，当为 `true` 或为空白则映射为默认 2 (标准档)。
   - 不直接删除旧键和强设默认值，是为了防止老用户更新后设置被静默重置而引入回归。

## 裁剪涂鸦页重构与组件拆分 (2024-10)

### 背景与痛点
原来 `CropScreen.kt` 超过 1000 行，将状态持有、数学矩阵变换、手势检测、Canvas 绘制、顶栏底栏以及预览弹窗全部混杂在一起，维护难度高且容易引入回归错误。此外，旧底部工具栏层级混乱、按钮权重相同，预览弹窗无法缩放，手柄不够直观。

### 决策内容
1. **模块与职能清晰拆分**：
   - `CropScreen.kt`：顶层调度、会话与异步保存状态管理；
   - `CropOverlay.kt`：专职 Canvas 绘制与手势拖拽（手柄命中测试、双层遮罩渲染）；
   - `CropToolbar.kt`：专职顶栏与底部工具栏交互；
   - `CropRect` 与 `MaskStroke`：作为模块内部核心数据模型，可见性统一调整为 `internal`，保持不可变契约（`val` + `copy()`）。
2. **两层操作底栏与手势防丢**：
   - 上层分段按钮切换「框选/涂鸦」模式；模式切换时强制自动提交尚未完成的 `activeStroke`，防止涂鸦笔迹因切模式丢失。
   - 下层操作区强化“确认”大按钮的视觉权重，辅助操作（重置/撤销/旋转/清空）对称分布两侧。
3. **预览体验升级**：
   - 预览从尺寸受限的 AlertDialog 改为近全屏 `Dialog`（`usePlatformDefaultWidth = false`），并借助 `rememberTransformableState` 和 `graphicsLayer` 支持平滑的双指缩放与平移查看。
4. **数学红线保留**：
   - `rotateNormalized` 及其推导注释、整图像素坐标减裁剪原点的换算逻辑绝对冻结，不顺手改动。

## 涂鸦实时渲染、对话拍照与流式思考卡顿优化 (2024-10)

### 1. 涂鸦笔迹实时渲染与模式可见性
- **问题**：旧手势协程捕获闭包外部陈旧状态，导致手势拖动时点未连续累加；且 Canvas 仅绘制 `size >= 2` 的笔迹，单点轻触被完全丢弃；非涂鸦模式下隐藏笔迹导致用户误以为涂鸦未生效。
- **决策**：
  - 手势协程使用局部 `currentPoints` 收集点序列并实时回调更新 `activeStroke`，Canvas 实时渲染当前正在绘制的笔划；
  - 补充 `points.size == 1` 时绘制圆点（`drawCircle`），支持轻触涂抹；
  - 无论处于框选还是涂鸦模式，已确认的遮罩笔迹均在底层完整呈现，所见即所得。

### 2. 对话界面拍照附件复用
- **决策**：在对话输入栏附件选择弹窗（`AttachmentSourceSheet`）中增加「拍照」入口，使用系统 `ActivityResultContracts.TakePicture()` 与 `FileProvider`（`cacheDir/camera` 临时文件），拍摄完成后直接接入现有 `ChatViewModel.onAttachmentPicked(uri)` 附件流水线，零冗余复用图像压缩和多模态请求。

### 3. 流式思考 (Thinking) 降级与卡顿平滑优化
- **问题**：
  1. `complete` 流仅对正文 `deltas` 计数，思考输出阶段若有轻微网络等待易误判触发流式降级，导致重复发非流式请求卡顿数秒；
  2. 节流落库原采用增量追加导致多次嵌套 `<think>` 标签，数据污染；
  3. UI 在正文首个 token 到达时强行关闭思考块折叠动画，视觉产生突兀截断感。
- **决策**：
  - 将思考分片一并计入 `totalEmitted`，只要产生过思考或正文即永久禁止降级重发；
  - 节流落地改用全量更新（`updateAssistantContent`），统一合成 `<think>...</think>` 与正文；
  - `ThinkingBlock` 在流式生成期间保持展开状态，在整条流式输出完全结束（`!isStreaming`）时才自动收起，保障视觉平滑。

## 原生 A4 PDF 导出引擎与纯空白重做区、公式排版支持 (2024-10)

### 背景与痛点
1. **HTML 导出对新手门槛高**：之前仅保留单文件 HTML 导出，需要用户在手机浏览器中二次「分享 → 打印 → 另存为 PDF」，操作链路长、不易直观发现；
2. **答题/重做区格式诉求**：用户明确提出答题区不要任何横线或网格，只要干净的空白，便于自由书写作答，且留白高度需能动态调节；
3. **公式排版严禁乱码/破损**：LaTeX 公式排版在打印端至关重要，必须与正文字号等比对齐且支持异常降级，不能破坏源码。

### 决策内容
1. **纯原生 A4 PDF 导出引擎 ([PdfExporter.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/print/PdfExporter.kt))**：
   - 严格遵循 PRD 第 8 节和技术栈红线，使用 Android 原生 `android.graphics.pdf.PdfDocument` + `Canvas` 直接生成标准 A4（595 × 842 pt）矢量文件，不引入任何第三方依赖；
   - 保持左右边距 42 pt，可用宽度 511 pt；自动绘制带文档标题、日期的页眉及「第 N / M 页」页脚。
2. **纯空白重做区与动态留白调节**：
   - 彻底移除浅灰横线与网格线，重做区内部保持纯洁空白；
   - 留白区域高度严格绑定用户界面的 `blankHeightPt` 参数，支持动态调节排版间隙。
3. **高精度公式图文混排与源码回退机制**：
   - 批量调用 `MathRenderer.renderAll` 获取 KaTeX 渲染位图，经 `MathLayout.fit` 缩放，保证公式字母与正文文字 1:1 等大；
   - 使用 `MathLayout.lineHeightFor` 动态扩展包含高分式/上标行的行高，彻底消除图文与上下行文字重叠；
   - 独立公式（`display = true`）单独居中排版；若公式渲染遇到极端异常，自动回退为原始 LaTeX 源码文本排版，确保题目内容永远完整可读。
4. **两阶段排版切分与双格式支持**：
   - 排版器第一阶段按行安全切分、计算确切总页数 `M`，避免页底文字截断或孤题号遗留；
   - `PrintUiState` 与选项面板提供「PDF（推荐）」与「HTML」快捷切换，默认直出 PDF 并通过系统阅读器即刻预览和分享。

## 双核主页门户与纸质化背单词模块整合 (2024-10)

### 背景与诉求
用户拥有此前独立开发的《单词书》（考研 4356 词库、同根形近干扰项算法、背词/错词/检索/统计闭环），希望将其整套功能与纸质化设计风格完整搬入当前错题本 App 中。
核心诉求包括：
1. **主页双选项交互**：进入主页有「错题本」与「背单词」两个顶层选项，自由切换；
2. **纸质化设计美学**：全面引入温润暖纸（Warm Paper：暖米灰底 #F6F4EE、暖白纸卡片 #FFFEFB、靛蓝品牌色 #3B5BDB、四级掌握度色条）及护眼「深色墨砚夜读模式」（Dark Ink Paper）；
3. **纯本地化、零后端同步**：无远程云端、无账号体系，秒开且绝对私密；
4. **功能算法完整保留**：四层同词根干扰项抽取（排除同义二义性）、四选一模式、卡片翻转自测、答错 6~8 题短期重现、错词连对 3 次毕业出库、曾错本归档、熟词标记（☆）、4356 考研词全文检索与掌握度筛选、打卡与学习统计看板。

### 决策内容
1. **双核门户架构 ([MainPortalScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/portal/MainPortalScreen.kt))**：
   - 顶层设计暖纸风格双选项胶囊分段组件（`PortalSegmentedControl`），配合平滑 `Crossfade` 过渡；
   - 导航层 `Routes.HOME` 统一挂载 `MainPortalScreen`，错题本原有所有导航参数（拍照加题、裁剪、打印、AI对话、错题本筛选）100% 透传，互不破坏；
   - 错题本页设置零 WindowInsets 规避嵌套 AppBar 的间隙跳动，背单词页在顶栏提供设置入口。
2. **纸质化设计系统与深色模式 ([Color.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/theme/Color.kt), [Theme.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/theme/Theme.kt))**：
   - 注入 `PaperBgLight`、`PaperCardLight`、`PaperPrimary`、`PaperLv0~Lv3` 等经典纸质规范；
   - 扩展构建深色墨砚配色（`PaperBgDark` #19181D, `PaperCardDark` #24232C, `PaperTextPrimaryDark` #ECE9E2），通过 `isSystemInDarkTheme()` 自适应，兼顾夜晚沉浸阅读与 OLED 护眼省电。
3. **独立单机数据库设计 ([WordbookDatabase.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/data/WordbookDatabase.kt))**：
   - 不修改已有 `MistakeBookDatabase`（版本 3）旧表，避免触发复杂的 Schema 迁移指纹变更；
   - 创建独立的单机轻量 Room 库 `wordbook_local.db`（版本 1，`WordProgress` 与 `WordStudyLog` 表），彻底隔离错题本与单词书的数据生命周期与备份逻辑，安全可靠。
4. **算法高保真还原与测试解耦 ([VocabRepository.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/data/VocabRepository.kt))**：
   - 将 `vocab.json`（4356 词）与 `vocab-index.json` 作为本地内置 Assets 加载；
   - 完整还原 L1（同根同词性）-> L2（同根跨词性）-> L3（同词性）-> L4（全库兜底）四层智能干扰项生成算法，并通过 `synGroups` 严格剔除同义词，杜绝双答案逻辑缺陷；
   - 提供单元测试重载构造函数，使算法能够脱离 Android Context 在纯 JVM 单元测试中执行校验。
5. **刷题跟手节奏与深色反馈对比度重塑 ([WordbookViewModel.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookViewModel.kt), [WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 答对即时触发 380ms 极快且跟手的自动下一题调度，消除用户反复手动点击下一题的疲劳感；答错时保留「下一题」手动推进按钮，方便从容看清错因与正解；
   - 彻底修复深色模式下作答反馈卡片误用浅底导致白字完全看不清的问题：引入深浅色自适应容器与高对比度文字体系（深色模式下正确为沉浸墨绿 `#133221` 配亮薄荷白字 `#E6FCED`，错误为暗绯红 `#381518` 配浅粉白字 `#FFE8E8`，对比度均 > 10:1）。

## 全局深色模式可配置化与动态切换 (2024-10)

### 背景与诉求
用户要求深色模式必须能够在「设置」页面自由调节，支持三档选项：
1. **跟随系统**：默认选项，随 Android 系统主题自适应切换浅色暖纸或深色墨砚；
2. **浅色模式**：强制使用温润暖纸（Warm Paper）经典浅色风格，不受系统暗色模式影响；
3. **深色模式**：强制使用墨砚夜读（Dark Ink Paper）深色护眼风格，不受系统亮色模式影响。

### 决策内容
1. **枚举定义与持久化存储 ([SettingsSnapshot.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/data/prefs/SettingsSnapshot.kt), [SettingsStore.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/data/prefs/SettingsStore.kt))**：
   - 定义 `enum class ThemeMode { SYSTEM, LIGHT, DARK }`，默认值为 `SYSTEM`；
   - 通过 DataStore Preferences 键 `theme_mode` 持久化字符串（`"system"`, `"light"`, `"dark"`），保证重启后状态长久保持；
   - `SettingsStore` 暴露 `suspend fun setThemeMode(mode: ThemeMode)` 与响应式快照。
2. **Activity 级响应式主题绑定 ([MainActivity.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/MainActivity.kt))**：
   - 在 `MainActivity.setContent` 中以 Compose 状态收集 `settingsStore.settings`；
   - 动态计算 `isDark = when (settings.themeMode) { SYSTEM -> isSystemInDarkTheme(), LIGHT -> false, DARK -> true }`；
   - 注入 `MistakeBookTheme(darkTheme = isDark)`，在用户切换设置项时实现毫秒级即时全局重绘生效，无需重启应用；
   - 状态栏图标明暗通过 `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme` 实时自适应同步。
3. **全局深暗态环境透传 ([Theme.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/theme/Theme.kt), [WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 在 `MistakeBookTheme` 中提供 `LocalDarkTheme = compositionLocalOf { false }`；
   - 将《单词书》模块中直接调用 `isSystemInDarkTheme()` 的局部组件全量迁移至 `LocalDarkTheme.current`，保证无论系统处于何种模式，只要在应用设置中强制选择浅色或深色，《单词书》词库卡片与反馈色条均同频即时响应。
4. **设置界面现代卡片式交互 ([SettingsSections.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/settings/SettingsSections.kt), [SettingsScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt))**：
   - 在设置页中提供「外观与主题」专属配置卡片；
   - 采用 3 个并排的现代化微交互选择卡片（`ThemeOptionCard`），配备 `BrightnessAuto`、`LightMode`、`DarkMode` 专属矢量图标与主色加粗选中高亮轮廓；
   - 底部动态附注当前主题档位的说明文案，指引明确。

## 单词刷题防连续重现与错词复习权重优化 (2024-10)

### 背景与痛点
用户反馈：“一个单词错了就会连着出现三次，权重太重了”。
经代码溯源定位：
1. **连续出现成因**：原先答错单词被直接写入 `isWrongBook = true`，而选题算法在无冷却机制下以 70% 极高概率从错题本随机抽取。当错词较少（如仅 1~2 词）时，该错词在接下来的每一道题有 70% 概率被立刻抽中，导致做错后下一题立刻再次出现；
2. **连现三次成因**：原规则硬编码了 `newReps >= 3`（必须连续做对 3 次才能毕业移出错词本），配合 70% 抽取率，造成该词几乎必然在做错后连续霸屏 3 题；
3. **延迟队列脱节**：答错时虽向 `deferQueue` 推送了 +6 题的延迟重现项，但通用的错题本随机抽取未过滤该队列，导致刚做错的词被立即提前抽出，随后在 +6 题时再次被抛出，造成严重过度练习疲劳。

### 决策内容
1. **最近出题防连续滑动窗口 (Cooldown Buffer, [WordbookViewModel.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookViewModel.kt))**：
   - 引入容量为 8 题的 `recentWords: ArrayDeque<String>` 冷却滑动窗口；
   - 无论是新词还是错词，一旦答题完毕立即入窗保护，8 题内物理禁止再次抽取（彻底杜绝下一题连续做同一道词）；
2. **大幅降低错词复习权重**：
   - 将错词本随机抽取概率由原先霸占主线的 70% 调降为 20%（温和穿插复习，80% 留给新词推进主线）；
   - 错词抽取候选池中必须严格排除正在 `recentWords` 冷却中以及已在 `deferQueue` 排队等待重现的词，两套队列完全解耦协同；
3. **错词复习过关门槛轻量化**：
   - 将移出错词本的条件由生硬的 `newReps >= 3` 优化为 `newReps >= 1`；
   - 答错进入错词本并在 8 题后安排 1 次间隔强化；当它以间隔复习形式出现并被用户正确作答时，说明已强化记忆，立刻从活跃错词本毕业（同时长期保留在「曾错词」归档及学习历史中），大幅减轻刷题挫败感。

## 主门户与背单词页面左右手势滑动切换支持 (2024-10)

### 背景与痛点
用户反馈：“左右滑动不能切换页面”。
经排查：
1. **主门户静态切换**：原 `MainPortalScreen` 仅使用 `Crossfade` 和顶部单选分段胶囊控制页面可见性，未挂载手势监听，在「错题本」与「背单词」之间左右划动无法触发任何翻页响应；
2. **单词书内部静态切换**：原 `WordbookScreen` 使用 `Box` + `when (state.currentTab)` 根据选中状态显示对应的 4 个功能模块（学习刷题、错题本、词库检索、学习统计），用户同样无法通过左右滑动手势在各 Tab 间穿梭。

### 决策内容
1. **全链路迁移至 Compose Foundation `HorizontalPager` ([MainPortalScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/portal/MainPortalScreen.kt), [WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 顶层 `MainPortalScreen` 引入 `HorizontalPager(state = pagerState)`（2 页：错题本与背单词）；
   - 单词书内部 `WordbookScreen` 同样引入 `HorizontalPager(state = pagerState)`（4 页：学习刷题、错题本、词库检索、学习统计）；
2. **标签指示器与滑动手势双向严格同步**：
   - 点击标签胶囊/Tab 时：触发协程执行 `pagerState.animateScrollToPage(index)`，享受丝滑位移动画；
   - 用户屏幕手势划动时：通过 `LaunchedEffect(pagerState.currentPage)` 监听页码变更，实时通知 ViewModel 更新当前业务状态，保证顶部高亮胶囊与下划线游标毫秒级与手势同步；
3. **利用 Nested Scroll 实现自然的分层滑动冒泡**：
   - Compose 的 `HorizontalPager` 原生支持嵌套滚动（NestedScrollConnection）。当用户在《单词书》首页（学习刷题）向右滑至边缘时，滑动事件自动冒泡至外层 `MainPortalScreen`，自然顺滑地切回「错题本」页面；而在单词书各子页面之间滑动则在内层切换，符合现代移动端手势交互直觉。

## 单词书「义项辨析」模式实现与熟词僻义算法 (2024-10)

### 背景与痛点
用户指出：“我记得是有义项辨析的啊……”。
对比原单词书 Web 端实现，原有学习系统包含三大核心刷题模式：
1. **常规四选一（quiz）**：题干给英文单词，选项给多义项拼接释义；
2. **卡片自测（card）**：正面单字，翻面呈现全释义并由用户四档打分；
3. **义项辨析（sense）**：题干给**特写单一义项**（如 `vt. 寄往；致辞`），选项为 4 个英文单词。

常规四选一存在明显的学习假阳性（False Positive）漏洞：
- 选项释义是「n. 地址；vt. 寄往；致辞」这种完整拼接串，只要认出第一个最常见的义项（address = 地址）就能猜中判对；
- 导致考研英语最关键的“熟词僻义”根本未被真正掌握，却被算法误标记为已掌握。

### 决策内容
1. **出题方向与唯一正解硬约束 ([VocabRepository.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/data/VocabRepository.kt))**：
   - 方向反转：题干展现单一特定义项，选项为英文单词（反向出题无法从多个中文释义间取巧）；
   - **硬约束（绝无双正确答案）**：通过 `splitSenses(meaning)` 拆分多义项并经 `normSense` 剔除标点空格进行归一化；在为目标词挑选 3 个干扰项时，严格排除释义中也包含该题干义项的任何词汇，同时排除同义组词，确保答案物理唯一。
2. **多义词池与调度过滤协同 ([WordbookViewModel.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookViewModel.kt))**：
   - 题库池锁定：从内置 4356 词中精确筛选出包含 2 个及以上义项的 1951 个高频多义词（占比 44.8%）；
   - 在「义项辨析」模式下，选题逻辑（冷却窗口、错词复习、未掌握新词）自动追加多义词判定；
   - 答对继续触发紧凑的 380ms 自动切题；答错直接计入错词本与 8 题短期重现队列。
3. **沉浸式纸质卡片与反馈排版 ([WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 顶部提供「四选一模式 / 卡片自测 / 义项辨析」三联单选分段胶囊；
   - 题干卡片附带专属 `🏷️ 义项辨析 · 熟词僻义自测` 书签标志与指导提示；
   - 选项卡片主文案为大号衬线英文单词，作答后即时展开该选项对应的中文释义，供学习者迅速对比混淆项；
   - 答错时提供高对比度自适应警示卡片，详尽列出 `正确答案：word（完整释义）` 与「下一题」推进按钮。

## 签名一致性与 UI 视觉层级高级审美升级 (2024-10)

### 背景与痛点
1. **覆盖安装报「签名不一致」**：GitHub Actions 运行在干净容器中，若未配置 release keystore，每次 build 自动生成随机 `debug.keystore`，导致打出的 APK 每次签名指纹 SHA-256 均不相同，Android 系统校验时判定为非法覆盖安装而拒绝更新；
2. **顶栏多层堆叠冗余**：错题本页面原先同时渲染了 `MainPortalScreen` 顶栏与 `HomeScreen` 内置 `TopAppBar`，导致上下两层顶栏强行堆叠，视觉逼仄拥挤；
3. **视觉高级感不足**：单词书统计和卡片线条较粗糙生硬，缺乏呼吸感与高级纸质书质感。

### 决策内容
1. **固化 Debug 签名密钥 ([app/build.gradle.kts](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/build.gradle.kts))**：
   - 将稳定的 `signing/debug.keystore` 纳入仓库，并在 Gradle `signingConfigs.getByName("debug")` 中显式绑定；无论 CI 容器如何重建，产出的 APK 签名证书完全恒定一致，用户手机可无缝直接覆盖升级。
2. **消除双顶栏并统一操作架构 ([MainPortalScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/portal/MainPortalScreen.kt), [HomeScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/home/HomeScreen.kt))**：
   - 顶层单顶栏整合：左侧呈现当前模块标题（错题本/单词书），中部放置分段滑动胶囊，右侧按上下文自适应提供快捷操作（错题本显示打印+AI聊天+设置，单词书显示设置）；
   - 移除 `HomeScreen` 冗余的整个 `TopAppBar`，将搜索框自然嵌入首屏内容顶部，配合圆角与微透底色，实现轻盈舒展的沉浸式体验。
3. **单词书纸质化高级美感重构 ([WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 顶部统计条重构为 3 个色彩柔和、微圆角（12.dp）的雅致统计胶囊（今日已学 / 待复习错词 / 已掌握），视觉呼吸感显著提升；
   - 移除 Tab 栏生硬分割线，增加卡片微阴影与 20.dp 圆润导角，大幅提升视觉精致度。

## 返回键手势多级导航闭环与并发状态机加固 (2024-10)

### 背景与痛点
1. **返回键冒泡退出**：原先处于背单词页或单词书子 Tab（错词/词库/统计）时，按返回键直接关闭应用；
2. **义项辨析题目穿帮**：处于义项模式但若针对目标词构建 Sense 题失败，原逻辑回退到常规单选，导致题干显示义项而选项是常规释义，题意错位；
3. **词库大列表性能**：4356 词库全量检索与关联缺乏防抖，连续输入引起并发覆盖；
4. **答题并发竞争**：快速点击选项或打分未加防重入锁，容易触发跳题或重复记录。

### 决策内容
1. **多级 BackHandler 嵌套导航 ([MainPortalScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/ui/portal/MainPortalScreen.kt), [WordbookScreen.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookScreen.kt))**：
   - 形成 `子Tab (1/2/3) -> 单词书主Tab (0) -> 错题本首页 (0) -> 退出应用` 的平滑回退链路；
2. **义项辨析 10 次多义词轮转与全局保底 ([WordbookViewModel.kt](file:///e:/mistake-recorder-0.0.3/mistake-recorder/app/src/main/java/com/mistakebook/wordbook/ui/WordbookViewModel.kt))**：
   - 确保 SENSE 模式必定构建出合规的多义词题，绝不向下穿透至常规单选；无题时安全空状态兜底；
3. **词库检索 200ms 防抖与 Sequence 惰性懒截取**：
   - 空搜索时仅懒截取前 100 词，满足后短路终止，大幅降低内存分配；
4. **AtomicBoolean 原子状态锁与 Job 协同**：
   - `selectOption` 与 `rateCard` 使用 CAS 原子保护，防止极速连击导致并发写入与跳题。

## 签名体系统一收口：release 密钥为唯一签名源 (2026-10-22)

### 背景与痛点
1. **「每次升级都要卸载重装、丢数据」**：v0.0.11 及之前的所有发布包都由 CI 随机生成的 debug 密钥签名（干净容器无固定 keystore），每个包签名都不同，Android 判定非法覆盖安装；
2. **稳定 debug 密钥方案落空**：2024-10 的决策写的是「将 `signing/debug.keystore` 纳入仓库」，但 `.gitignore` 的 `*.keystore` 规则从未被绕过，该文件实际从未进过仓库，对应代码路径在 CI 中是死代码；
3. **发布密钥断代**：`signing/expected-cert-sha256.txt` 记录的旧钥匙（`00:6B:BF…`）随沙箱重置丢失，若照旧配置 Secrets，CI 指纹闸门会在 main 推送时硬失败；
4. **Release 说明被覆盖**：`Attach APK to Release` 步骤自带 `body` 参数，会把 API 撰写的版本说明整体替换成安装指引。

### 决策内容
1. **唯一签名源 = release 密钥**（`CN=mistakebook, O=ausyeah`，SHA256 `E3:BA:30…`，口令随《签名 Secrets 添加指南》交付，永进仓库的只有指纹）：
   - v0.0.12 安装包由维护者在本地用该密钥重签（v2+v3 双 scheme，minSdk 26 无需 v1）；
   - 配置 4 个 Secrets 后 CI 自动产出同签名包，用户此后永远直接覆盖升级；
   - 放弃「仓库内置稳定 debug 密钥」路线，杜绝两套合法签名并存的混淆；
2. **CI 三道签名闸门**：
   - tag 构建（对外发布）未配 Secrets 直接失败，绝不再产出随机 debug 签名的发布包；
   - 指纹一致性校验从「仅 main」扩大到「main + 所有 tag」，防换钥发布；
   - `Attach APK to Release` 去掉 `body`/`generate_release_notes`，版本说明只由发版流程通过 API 撰写；
3. **指纹记录同步换新**并注明旧钥匙从未到达任何用户（v0.0.11 前均为随机 debug 签名），更换不影响已安装用户。
