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
