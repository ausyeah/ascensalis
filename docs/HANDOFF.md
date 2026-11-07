# Ascensalis 交接上下文（v0.0.131 已发布，写给下一个接手的 agent）

> 读完这一份即可继续工作，不需要回看历史会话。
> 更新时间：2026-11-07。v0.0.131 已发布（CI 绿，签名 APK 已挂 Release）；同日完成全流程模拟器自测（抓到并修复拍题识别 Key 读取 bug）、左滑三处统一为完整图形拼贴、README 全面重写。
> 产品全景见 [`PRODUCT.md`](PRODUCT.md)，逐版本记录见 [`CHANGELOG.md`](CHANGELOG.md)，设计取舍见 [`DECISIONS.md`](DECISIONS.md)。

---

## 1. 项目概况

- **App**：Ascensalis（错题本 Android 应用），Kotlin + Jetpack Compose (Material3) + Room + DataStore + Retrofit/OkHttp(SSE) + kotlinx.serialization + CameraX + WebView(KaTeX)
- **GitHub**：`ausyeah/ascensalis`（main 直推是既有节奏，不开 PR；打 tag 触发 CI 出签名 APK 挂 Release）
- **唯一工作副本**：`C:/Users/26315/application/错题本/ascensalis`（本目录，HEAD 已推，工作树干净）
- **当前版本**：versionCode 131 / "0.0.131"（`app/build.gradle.kts` 96-98 行；版本提交只准改这两行）
- **本机 private 目录**：`local/`（gitignore）——模拟器工具 APK、测试素材 PDF、API Key 清单（`local/keys/api-keys.md`）、DataStore 备份。`错题本/` 上层还有 autopilot-wt、emu-shots 等历史杂物，与日常工作无关可忽略
- 测试素材：`local/assets/ep_math1_mock.pdf`（6 页考研数学模拟卷，已推进模拟器 `/sdcard/Download/` 与资料库）

## 2. 硬性规则（每版都适用，违者返工）

1. **不引入任何新依赖**（PRD 之外的库一个都不加；docx/pdf 解析、protobuf 注入都是零依赖手写）
2. 界面文案全中文，**所有文案进 `app/src/main/res/values/strings.xml`**（该文件 CRLF）
3. **每个行为修复必须配 JVM 单元测试**（契约测试大量使用 `readSource` 断言源码字面量——改被钉住的字面量要同步改测试）
4. 注释写「为什么」不写「是什么」；不新增 TODO/FIXME
5. 中文提交信息，前缀 `fix:`/`feat:`/`docs:`/`chore:`；**版本号改动单独一个 commit 只动两行**
6. `@Serializable` 持久化类（PosterContent/WorkData 等）加字段必须带默认值
7. 每完成一个任务就推一次；用户说发版才打 tag

## 3. 本机环境（v0.0.131 时代实测可用）

### 构建与测试（本机现在能跑）
```
./gradlew assembleDebug --offline --no-configuration-cache
./gradlew :app:localTest --offline --no-configuration-cache -I .localtest.init.gradle.kts
# 当前基线：1679 用例，仅 13 个已知 layoutlib 截图基线失败（UiScreenshotTest×4、LazyReverseGeometryTest×5、SettingsLayoutScreenshotTest×4）——这是「全部通过」的定义
```

### 模拟器（完整操作手册）
- AVD `ascend35`（API 35, x86_64, pixel_6）；启动后 serial `emulator-5554`；app 包名 `com.mistakebook`
- **`hw.keyboard = yes`** 已开（PC 键盘直通，拼音组合可用）
- 中文输入法 **fcitx5**（`org.fcitx.fcitx5.android/.input.FcitxInputMethodService`，默认）+ **ADBKeyboard**（`com.android.adbkeyboard/.AdbIME`，自动化注入中文）；两个 APK 在 `local/tools/`
- 模拟器偶发崩溃（WHPX，本会话两次）：`adb devices` 空了就重启，数据不丢

**怎么操作（按宿主能力二选一）**：

A. 宿主有 android-emulator MCP 插件（本会话用的就是它）：
   - `android_start_emulator(ascend35)`——MCP 调用 30s 会超时，**属正常**，另开轮询 `adb shell getprop sys.boot_completed` 等 `1`
   - `android_build_and_run(projectDir, avd)` 一步到位（也可能超时，失败就手动 assembleDebug + install）
   - `android_install_app(apkPath)` / `android_screenshot` / `android_launch_app(com.mistakebook)`
   - UI 自动化：`android_ui_describe`（元素树）、`android_ui_resolve("文本")`（拿准确坐标）、`android_ui_tap(x,y)`——**别用缩略图坐标盲点**（截图有缩放，×1.2/×2.4 踩过多次）

B. 纯 adb（任何宿主都能跑，SDK 在 `C:/Users/26315/AppData/Local/Android/Sdk/platform-tools/adb.exe`）：
   ```
   emulator -avd ascend35 &                     # 启动（或 Android Studio Device Manager）
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb exec-out screencap -p > shot.png          # 截图
   adb shell input tap <x> <y> / input swipe ... / input keyevent 4(BACK) 3(HOME) 111(ESC)
   adb shell am start -n com.mistakebook/.ui.settings.SettingsActivity
   adb shell "logcat -d -t N"                    # 看日志（okhttp 行有每次网络调用）
   adb root + adb pull /data/data/com.mistakebook/...   # root 读写私有数据
   ```
- **UI 自动化要领**：导航别盲点坐标——先 `ui_describe`/`ui_resolve` 拿目标文本的真实坐标再 tap；中文注入 = `ime set com.android.adbkeyboard/.AdbIME` → `am broadcast -a ADB_INPUT_TEXT --es msg '中文'` → 用完切回 fcitx5；发消息类 tap 常因键盘顶起布局偏移，tap 前先截图确认目标位置
- 宿主机 shell 是 Git Bash：**adb 的远程路径**要 `MSYS_NO_PATHCONV=1` 或双斜杠，本地路径用 `C:/` 形式；heredoc 吃反斜杠（写脚本用 Write 工具）

### 网络（用户长期挂 VPN 的场景）
- 宿主机系统代理 `127.0.0.1:7900`（curl 加 `--proxy http://127.0.0.1:7900 --ssl-no-revoke`）；环境变量里的 `HTTPS_PROXY=127.0.0.1:7897` **是坏的**——git push / API 调用显式 `-c http.proxy=http://127.0.0.1:7900`
- **原则（用户原话）：MinerU/商汤等国内服务必须直连，只有 git 这类国外服务才走代理。** app 内 `NetworkRouter.DIRECT_HOSTS` 已含 mineru 全系 + `sensenova.cn` + 国内大厂；新增国内供应商记得加进名单
- GitHub API：`GH_TOKEN` 环境变量（取法 `powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('GH_TOKEN','User')"`）

### API Key（全部见 `local/keys/api-keys.md`）
- 已配置进模拟器 app：MinerU（拍题 OCR）、商汤（对话主渠道 + OCR 视觉槽）、Tavily（搜索）、OpenRouter（资料库 embedding）
- 已知坑：**商汤 flash 免费档 tpm/rpm 极小 → 429 是账号配额问题**；OpenRouter 免费上游偶发 502（换 nemotron-3 型号）

### 明文 DataStore 注入（配 key 的快捷法）
加密项（*_profiles_json、mineru_api_key 等）必须走 app UI；**明文项**可直接改文件：
1. `adb root` → pull `/data/data/com.mistakebook/files/datastore/mistakebook_settings.preferences_pb`
2. python 追加 protobuf 条目（map 语义后者覆盖；顶层 `0a <len>` map entry、key=field1、Value{field5:string}；varint 写法参考会话脚本）→ push 回
3. `chown u0_a152:u0_a152` + `chmod 600` + `chcon u:object_r:app_data_file:s0:c152,c256,c512,c768`（以文件原属主为准，先 stat）
4. `am force-stop` + 重启 app

## 4. 代码要领（近期改动集中地 + 高频坑）

- **左滑三处统一**（`ui/common/SwipeReveal.kt`）：`SwipeActionBlock`（共享块）+ `swipeActionTileShape()`（四角全圆 16dp）+ `SwipeActionGap=0.dp`（紧贴拼贴）+ `SwipeRevealCoordinator`（互斥：同列表同时只开一行，页面销毁自动收回）。**叠画已废弃**（用户给了设计图：「）（这样的两个都是完整图形的拼贴」）；块顺序必须主操作在左、危险操作在右。契约测试：`SwipeActionGeometryTest`、`ChatToolsTest.历史行左滑露出三个动作`
- **抽屉手势**：对话历史抽屉（ModalNavigationDrawer）内的行拖动必须 `enabled = drawerOpen`（否则抢抽屉开合手势 =「拉出卡住」）；右滑已收起行 → `closeDrawer()` 移交
- **拍题识别 Key**：`RecognitionEngine.mineruMarkdown` 读 key 的顺序 = 激活渠道 profile.apiKey 优先、`settings.mineruKey` 兜底——**新加识别路径都要走这个顺序**，否则复现「配了 Key 却报没配」
- **资料库 OCR 兜底**（v0.0.131）：扫描 PDF（对象流+无 ToUnicode 的 Word 导出卷）建索引时渲染页图 → 当前识别渠道逐页转写（`PdfPageRenderer` 共享渲染，上限 50 页）；`Progress.stage` 透出进度文案
- **会话数据安全**（v0.0.130）：chat_sessions **禁止整行 @Update**（并发丢更新）——一律定向 UPDATE（DAO 内注释有因）；归档清理双闸 `archivedAt < :before AND updatedAt < :before`；归档会话来信自动解归档
- ChatHistorySheet 抽屉：`ModalNavigationDrawer` + BackHandler 优先关抽屉
- Compose 高频坑（CI 翻过车）：awaitEachGesture 受限作用域不能直调挂起函数（snapTo 包 scope.launch）；stringResource 只能组合期调（onClick 协程里先 hoist）；AndroidView factory 只跑一次（用 remember+SideEffect 中转）；@Serializable 加字段带默认值
- GitHub API 返回的 shell 变量带 `\r`：比较前 `tr -d '\r'`

## 5. 发版流程（用户说「发版」时）

1. 全量 `localTest` 基线核对（13 个已知失败之外不许有红）
2. `app/build.gradle.kts` versionCode/versionName 递增（独立 commit）
3. push main → `git tag vX.Y.Z && git push origin vX.Y.Z`（注意代理）
4. 轮询 CI（API + `head_branch == tag`）到 success
5. 验证 Release assets 有 `mistakebook-vX.Y.Z-android.apk` → PATCH 中文 release notes（body 写临时 json 再提交）
6. 关闭对应 issue（修复说明 + APK 链接）

## 6. 当前状态与待办

**已完成**（截至 2026-11-07，全部已推送）：
- v0.0.131 发布：资料库扫描 PDF OCR 兜底、三处左滑统一叠画、新对话图标、桌面图标铺满
- v0.0.131 后（未发版）：拍题识别 Key 读取修复、抽屉手势让行、左滑改完整图形拼贴 + 互斥协调器、重命名块色系统一、sensenova 直连、缝隙归零
- README 全面重写 + 20 张截图（16 张新版重拍）

**待办 / 已知问题**：
1. **商汤 flash 429**：账号配额问题——等窗口放行或用户提额后，重跑拍题全流程可入库一道题并补拍 README 的 edit-result / question-detail / print-options / home-questions / upload / final-pdf（这 6 张暂用旧版截图，界面差异极小）
2. 聚合识别失败的 errorKind 一律标 NO_KEY（429 应标 RATE_LIMIT）——小改进未做
3. 识别失败页的「重试」按钮对旧任务有效；批量重试入口没有 UI
4. 模拟器偶发崩溃（WHPX），重启不丢数据
5. `PdfImporter` 无 JVM 单测（要 Robolectric = 新依赖，需用户拍板）

## 7. 工作流（下一批反馈来了照这个走）

1. 逐条确认需求 → 改代码（每任务一个 commit）
2. `compileDebugKotlin` → `localTest` 基线核对 → `assembleDebug` → 装模拟器实机验证（用户看模拟器测试，**每次改完必须保证模拟器是最新构建**）
3. 用户验证通过 → 按需发版（§5）
