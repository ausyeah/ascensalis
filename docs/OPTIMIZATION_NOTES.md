# v0.0.54 后续优化记录（Task A~D）

本文件记录 `improve/v0.0.54-math-svg-a11y-settings` 分支上四个优化的动机、测量数据与取舍。
只做优化与测试/文档，不合并、不发版由单独的版本步骤处理；未触碰滚动锚定代理的工作与资料库/成就墙 WIP。

---

## Task A：公式裁切 `trimTransparent` 的 alpha 边界（P0）

### 背景

`MathRenderer` 抓完 KaTeX 位图后用 `trimTransparent` 裁掉四周透明边，只留「墨水 + `INK_MARGIN_PX`」。
原实现用 `inkBoxOf`（口径 `alpha > 0`）求包围盒。风险：极低 alpha 的抗锯齿噪声 / 亚像素残影
会把包围盒撑大，导致裁切不紧、公式偏移、行内基线不齐（用户反馈「公式位置发飘」）。

### 两种口径的分工（本次决策）

没有简单地「改阈值」，而是把两个一直被混在一起的问题拆开：

| 关注点 | 用什么口径 | 为什么 |
|---|---|---|
| 图里到底有没有墨（判空白） | `alpha > 0`（`isBlankBySampling` / `inkBoxOf`，**字节级不变**） | 要灵敏：漏判=公式整块消失 |
| 裁剪裁到哪（定边界） | `alpha >= INK_ALPHA_THRESHOLD`（新增 `coreInkBox`） | 要稳：不能被一粒淡噪点带偏 |

- 新增 `const val INK_ALPHA_THRESHOLD = 8`（约 3% 不透明度）。KaTeX 笔画核心远浓于此；
  透明度抖动造成的孤立淡点通常低于此值。
- 新增纯函数 `coreInkBox(pixels, w, h, alphaThreshold = 8)`：只认达到阈值的像素。
- 从 `trimTransparent` 抽出纯几何 `trimWindow(box, marginPx, w, h)`：核心盒外扩 `INK_MARGIN_PX`
  再夹到位图边界；等于整图时返回 null（不裁）。
- `trimTransparent` 改为 `trimWindow(coreInkBox(...), INK_MARGIN_PX, w, h)`；全透明（无核心墨水）
  时 `trimWindow` 返回 null，原样返回不裁（双保险，与旧行为一致）。
- `INK_MARGIN_PX = 2` 不变：它在核心盒外再兜一圈，把被阈值滤掉的抗锯齿软边盖回来——
  **正常字形不会被裁秃，stray 噪点也不会撑大盒子**，两头都不得罪。
- `inkBoxOf` / `isBlankBySampling` / `BLANK_SAMPLE_GRID` **完全不动**：前者是全量口径（被测试守着），
  后两者的网格按任务要求不改。

### 数据（来自 `MathRendererTrimTest` 的 23 条纯函数测试）

- **alpha 扫描矩阵**（单像素，alpha ∈ {0,1,2,7,8,9,16,64,127,254,255}）：
  `coreInkBox` 非空 ⟺ alpha ≥ 8；`inkBoxOf` 非空 ⟺ alpha > 0。阈值 8 恰好切开「噪声」与「实体」。
- **噪声边框 + 中心**（60×40，边框 alpha=2、中心 alpha=255）：
  全量口径包围盒 = 整图 `(0,0)-(59,39)`（被噪声撑满）；核心口径 = 仅中心 `(20,10)-(39,29)`。
- **stray 噪点对比**：干净中心块 vs 同一块 + 远处一粒 alpha=2。
  核心口径 + 余量裁出**同一窗口**；而全量口径会被撑到含噪点 `(20,10)-(55,35)`——即旧行为的偏移源。
- **软边不回割**：核心 alpha=255 + 外围 2px、alpha=3 软边。`coreInkBox` 只框核心；
  `trimWindow(·, INK_MARGIN_PX)` 的窗口正好覆盖核心 + 2px 软边，软边一点没被裁秃，且尺寸 > 0。
- **安全降级**：全淡图（所有墨 < 阈值）`coreInkBox` 返回 null → 不裁、原样返回，绝不裁秃。

### 公开行为

`inkBoxOf` / `isBlankBySampling` / `InkBox` 的公开契约不变（`inkBoxOf` 字节级未改，既有 `InkBoxTest` 全绿）。
仅新增 `coreInkBox` / `trimWindow` / `INK_ALPHA_THRESHOLD`；`trimTransparent` 为私有方法，内部改走核心口径，
裁剪更紧但不改任何对外签名。既有 `InkBoxTest.极低alpha也算墨水`（守 alpha=1 全量口径）保持通过。

### 测试

`app/src/test/java/com/mistakebook/math/MathRendererTrimTest.kt`：23 条 `coreInkBox` / `trimWindow` /
集成测试（≥15 达标）。本地无 Android SDK，由 CI 的 `testDebugUnitTest` 校验。

---

## Task B：SVG 流式未闭合状态的重解析开销（P0/P1）

### 背景

`MarkdownParser.parseCached` 按完整文本做 LRU，流式每个 chunk 都是新字符串 → 全部 miss → 重跑整篇解析；
其中未闭合的 SVG（v0.0.54 新增的「围栏 svg」与「裸 `<svg>`」两条路径）会被 `sanitizeSvg` 反复全量正则清洗。
长 SVG + 高频 token 下，这是 O(n²) 的主线程正则开销。

### 测量（分析 + 计数器断言；本地无 Android SDK/JVM，精确耗时以 CI 为准）

- **闭合前**：每个 chunk 对不断变长的 SVG 前缀跑一次 `sanitizeSvg`（6 条正则 over 全串）。n 个 chunk 累计字符量 ≈ Σ prefix ≈ O(n²)。
- **闭合后**：SVG 源码已稳定，但每个后续 chunk 重解析整篇仍会对同一段闭合 SVG 再跑一次全量清洗——同样是 O(n²)。

### 方案（两处叠加，都不改输出语义、都不破安全底线）

1. **`SvgSanitizer` 按 source 缓存清洗结果**（`androidx.collection.LruCache`，64 条）。sanitize 是纯函数，
   同输入同输出，缓存不改变任何结果。闭合后 SVG 源码不变 → 重复清洗命中缓存，「每 chunk 一次全量」
   降成「一次全量 + 其余 O(1) 查表」。
2. **超长未闭合 SVG 先不产出**（`MarkdownModel.unclosedSvgBlockOrNull`，上限 `UNCLOSED_SVG_EMIT_LIMIT = 32KB`）：
   - 空 → 不产出；≤ 上限 → 照旧清洗后产出（有内容就先渲染，**行为不变**）；
   - 超长 → 返回 null（界面留空占位），等 `</svg>` / 围栏闭合后走闭合路径整段清洗产出，自然恢复。
   把闭合前每 chunk 开销从 O(全长) 压到 O(1)（超长）或 O(上限)（小图）。

**安全底线**：不产出 = 没有任何未清洗 / 半截的危险内容进入渲染层；一旦产出必是过了 `sanitizeSvg`
全量清洗的内容。`<script>` / `on*` / `javascript:` / 外链 image 在流式各阶段（半开标签 / 半截 / 未闭合 / 闭合）
都被清掉（有测试守着）。已知限制（**既有行为，非本次回归**）：若 `<script>` 自身也未闭合，sanitize 的正则
匹配不到闭合标签而保留开头——但 SvgBlock 走 AndroidSVG 解码，本就不执行脚本；且超长未闭合已被整体跳过。

### 数据（`SvgStreamPerfTest` 的计数器断言）

- `sanitize 纯函数结果按 source 缓存`：同一 source 调 2 次 → `sanitizeCallCount = 2` 但 `sanitizeCharCount = 源长度`（只过一遍正则）。
- `闭合后每 chunk 追加正文_闭合 svg 只清洗一次`：闭合后追加 50 段正文，`sanitizeCharCount` 不再增长（50 次全命中缓存）。
- `超长未闭合 svg 流式期间不产出_闭合后恢复`：>32KB 未闭合 svg 分 30 个前缀喂入 → 产出 0 个图形块、`sanitizeCharCount = 0`；闭合后产出完整图。
- `裸 svg 未闭合超长也不产出` / `闭合时产出完整 svg 不因上限截断` / `小 svg 未闭合仍照旧渲染` / `非 svg 代码块不受上限影响`：行为保持。
- `流式各阶段危险内容都被清掉`：4 个流式阶段均无 script/on*/javascript:/外链 image，且正常几何与 xmlns 补齐仍在。

### 测试

`app/src/test/java/com/mistakebook/markdown/SvgStreamPerfTest.kt`：8 条（缓存、闭合后不重复清洗、超长降级、
裸 svg 降级、小 svg 保持、非 svg 代码保持、闭合不截断、流式各阶段安全）。既有 `SvgSanitizerTest` / `MarkdownSvgBlockTest` 不改、应全绿。

---

## Task C：拍照页无障碍（P1）

### 审计结论（v0.0.54 重构后的拍照页）

可点击图标按钮逐个过：快门 / 切换摄像头 / 相册 / 取消 / 闪光灯——**都已有 contentDescription 且走 `strings.xml`**（无硬编码中文）。
OCR 增强档是分段按钮、带文字标签；取景角标 / 九宫格是装饰 Canvas（无交互，正确地为 null）。
真正缺的是下面几项，本次补上：

### 改动

1. **触控目标 ≥ 48dp**：`FrostyCircleButton` 默认 44dp → **48dp**（Android 触摸目标下限；相册 / 关闭本就用 52dp，不动）。视觉仅 4dp 微调，不涉布局重构。
2. **开关类控件的 stateDescription**：闪光灯由「状态塞进 contentDescription」改成——
   contentDescription 固定「闪光灯」（新增 `capture_flash`），档位走 `stateDescription`（开 / 关 / 自动）。
   朗读更干净（不再「闪光灯开按钮」这种把状态念进标签）。档位映射提成纯函数 `flashStateDescriptionRes`，可 JVM 单测。
3. **禁用态可被 TalkBack 识别**：`FrostyCircleButton` / `ShutterButton` 在 `enabled = false`（无闪光灯硬件 / 相机未就绪 / 拍摄中）时打上 `disabled()` 语义，并设 `role = Role.Button`。
4. **语义合并**：两个按钮加 `semantics(mergeDescendants = true)`，确保内部图标的 contentDescription / stateDescription 真的成为按钮节点的朗读内容。

### 测试

`app/src/test/java/com/mistakebook/ui/capture/CaptureA11yTest.kt`：4 条 JVM 契约测试（CI 可跑）——
三档 stateDescription 各取对资源、档位资源互异、控件名与档位分开、五个图标标签非零且互异。

**未覆盖 / 风险**：节点级语义（contentDescription 是否真被合并朗读、禁用态朗读文案）需真机 / androidTest 验证；
本机与 CI 均无安卓设备、无 Robolectric（不新增依赖），故以资源契约测试 + 代码评审兜底，未加 androidTest（CI 不编译、无法验证，避免引入未验证代码）。
