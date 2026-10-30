# 交接：Ascensalis（错题本 Android App）— 当前状态 v0.0.80

> 这份文档写给**接手本项目的下一个 AI / 开发者**。自包含，不依赖任何会话历史。
> 读完这一份即可无缝开工。

---

## 1. 一句话现状

**v0.0.80 已完成开发与全量契约守护，main 分支准备就绪。**
当前基线：versionCode **80** / versionName **0.0.80**。

## 2. 本版本（v0.0.80）完成要点

1. **HTML 渲染自适应与防截断重构**（`HtmlSanitizer.kt`, `HtmlBlock.kt`）：
   - 彻底修复 `document.body.scrollHeight`（CSS 像素，等价于 dp）被 Compose `with(density) { toDp() }` 二次除以密度导致在 xxhdpi/xxxhdpi 屏幕上被严重截断 2/3 的底层问题；
   - 注入 `ResizeObserver` 与 `HtmlBridge` JavascriptInterface，异步加载内容或字体就绪时主动双向同步真实高度；
   - HTML 容器补充 `padding: 4px 2px 20px 2px` 与安全缓冲，超链接通过 `shouldOverrideUrlLoading` 外部浏览器唤起。

2. **思考过程折叠异常与底边重锚定**（`ChatScreen.kt`）：
   - 移除 `ThinkingBlock` 与 `reverseLayout = true` 产生严重冲突的多帧高度动画 `animateContentSize()`；
   - 新增 `onToggleThinking` 回调，用户点击“收起”时立即协同 `AutoScrollCoordinator` 恢复跟随并执行 `listState.scrollToItem(0, 0)`，防止收起后的折叠条滑入屏幕可视区域下方。

3. **主题色圆球排版挤压修复**（`SettingsSections.kt`）：
   - 将原单行 8 个色块重构为双行每行 4 个色块（`chunked(4)`），色块尺寸增至 `34.dp`，间距充足，彻底解决窄屏第 8 个色块被挤压成椭圆或被切出的显示异常。

4. **MinerU 配置全量更名为 OCR 配置**（`strings.xml`, `SettingsScreen.kt`, `README.md`）：
   - 将应用内所有针对 MinerU 的配置项与文案标题统一规范为“OCR 配置”（`OCR API Key`、`OCR 用量`、`测试 OCR`）；
   - 在设置与 README 中同步说明 OCR 配置支持 MinerU / DeepSeek OCR 等多种实现。

5. **番茄钟左右滑动手势支持**（`PomodoroScreen.kt`）：
   - 引入 `HorizontalPager(state = pagerState)` 取代静态 `when(tab)` 切换；
   - 顶部 `TabRow` 与左右滑屏双向同步，并具备运行状态防误触互锁逻辑。

6. **对话拍照裁切直通大模型 & 移除“不裁切下一张”**（`MistakeBookNavHost.kt`, `CropScreen.kt`, `CropToolbar.kt`, `ChatViewModel.kt`, `ChatRepository.kt`）：
   - 修复 `submitChatBatch` 未返回 ChatScreen 导致卡在裁剪页误触提交 OCR 错题识别的 bug；
   - 连拍裁切完成后自动弹栈返回会话，裁切图直接转为 Data URL 喂给大模型多模态对话，彻底杜绝发给大模型前误进 OCR 错题管线；
   - 彻底移除“不裁切下一张”跳过入口。

7. **连拍完成按钮排版与拍照页极简 UI 重构**（`CaptureScreen.kt`）：
   - “连拍完成”按钮下移至底部快门行右侧，没拍照时显示灰色且不可点击，拍完后高亮显示张数并启用；
   - 关闭（X）移至顶部左侧，闪光灯与前后摄移至顶部右侧；
   - 去除相机控制按钮中花哨的主题高光色，统一采用纯白/黑玻半透明极简磨砂质感；快门按钮升级为经典相机双环实心白圆盘。

8. **测试防线**：
   - 新增 `Version80FeaturesContractTest.kt`，全覆盖守护 HTML 桥接防截断、思考折叠回滚、双排主题色、OCR 更名、番茄钟滑动及拍照直通契约。

## 3. 环境与发版铁律

- 活跃路径：`C:\Users\26315\application\work\ascensalis-opt`（严禁触碰 `ascensalis` 旧目录）。
- 凭据提取：Windows 凭据管理器 `git credential fill`。
- 推送命令：`cmd /c scripts\push-retry.cmd`。
- Tag 发布命令：`powershell -ExecutionPolicy Bypass -File scripts\tag-retry.ps1 -Tag "v0.0.80"`。
