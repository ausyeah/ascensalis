<div align="center">

# 登临 Ascensalis

### 拍下错题它自己认，单词跟着计划背，专注计时还有数据——一个 App 管学习。

**Android 8.0+ · 单机应用 · 不注册账号 · 数据只存在你手机里**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/ausyeah/ascensalis/android.yml?branch=main&label=CI)](https://github.com/ausyeah/ascensalis/actions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://github.com/ausyeah/ascensalis/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://github.com/ausyeah/ascensalis)

**[下载最新版 APK](https://github.com/ausyeah/ascensalis/releases/latest)** · 直接覆盖安装，不丢数据

</div>

---

## 这是个什么 App

给埋头苦读的人做的一个学习工具箱，目前有这些趁手的工具：

| 工具 | 干什么用 |
|---|---|
| **错题本** | 拍张照、选好题目，App 自动认出文字和公式，大模型顺手帮你整理成标准题型；攒着随时翻，**左滑**置顶/归档、**右滑**直接看原题图（点一下全屏放大），勾选几下就能排版成 A4 打印重做 |
| **AI 对话** | 题目看不懂？直接问。多渠道多 Key 大模型、能发图片和文档、支持联网搜索与生图，回答边想边流出；长对话自动压缩上下文、回答打转自动掐断，也可以开临时会话随手问 |
| **记忆** | 让它长期记住你的背景、目标和偏好：一句话交给模型改写成分节文档，每轮对话自动带上 |
| **资料库** | 把教材、讲义、试卷（md / txt / pdf / docx）存进 App，对话时 @ 它让 AI 参考；建好向量索引后**语义检索**——用中文问，英文资料也能命中 |
| **背单词** | 四种考法随机出题（认词、选义、拼写、辨析），答错的自动收进复习队列，跟着记忆曲线安排下次见面 |
| **番茄钟** | 专注计时 + 白噪音，按学科记录每次专注；今天学多久、这周什么走势、哪科投入最多，图表一看就知道；归档任务可随时找回 |
| **复习与周报** | 按遗忘曲线排复习档期，今日该复习哪几道一目了然；AI 还能按薄弱知识点生成复习排期、知识体系思维导图与每周学习周报 |
| **成就墙** | 100 枚徽章覆盖刷题、专注、背单词、对话、资料库与使用习惯，学习里程碑一眼可见 |

**不联网也能用**：错题本支持纯手动录入，背单词、番茄钟完全本地。联网能力（题目识别、AI 讲解、生图）需要填自己的 API Key——Key 只存在本机加密空间里，不上传、不进日志。

---

## 截图

> 均为 Android 模拟器实拍（v0.0.142 版本），示例内容为虚构数据。

### 错题本：拍照识别 → 题库 → 打印

<table>
<tr>
<td align="center"><img src="docs/screenshots/home-overview.jpg" width="320" alt="错题本主页"><br><sub><b>主页</b> · 考试倒计时 / 搜索 / 待复习入口</sub></td>
<td align="center"><img src="docs/screenshots/filter.jpg" width="320" alt="筛选"><br><sub><b>筛选</b> · 按学科 / 掌握度 / 错题本切片</sub></td>
<td align="center"><img src="docs/screenshots/add-sheet.jpg" width="320" alt="添加入口"><br><sub><b>添加</b> · 拍题 / 相册 / PDF / 手动录入</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/crop.jpg" width="320" alt="框选裁切"><br><sub><b>裁切</b> · 拖四角框选题目，三档增强</sub></td>
<td align="center"><img src="docs/screenshots/question-edit.jpg" width="320" alt="核对识别结果"><br><sub><b>核对</b> · 识别结果逐条可改，公式实时预览</sub></td>
<td align="center"><img src="docs/screenshots/detail.jpg" width="320" alt="题目详情"><br><sub><b>详情</b> · 原题图 / 题干 / 答案，公式排版渲染</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/swipe-actions.jpg" width="320" alt="左滑操作"><br><sub><b>左滑</b> · 置顶与归档，归档两步确认</sub></td>
<td align="center"><img src="docs/screenshots/image-preview.jpg" width="320" alt="右滑看题图"><br><sub><b>右滑</b> · 就地摊开原题图，右侧留拉手</sub></td>
<td align="center"><img src="docs/screenshots/image-fullscreen.jpg" width="320" alt="全屏看图"><br><sub><b>看题图</b> · 点图全屏，双指缩放，点一下退出</sub></td>
</tr>
</table>

### 复习、打印与统计

<table>
<tr>
<td align="center"><img src="docs/screenshots/review.jpg" width="320" alt="今日复习"><br><sub><b>复习</b> · 会 / 模糊 / 不会 三档自评，自动排下次</sub></td>
<td align="center"><img src="docs/screenshots/exam-plan.jpg" width="320" alt="复习排期"><br><sub><b>排期</b> · 按考试日期倒推复习档期</sub></td>
<td align="center"><img src="docs/screenshots/print.jpg" width="320" alt="打印排版"><br><sub><b>打印</b> · 字号字体 / 答案解析 / 留白重做</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/stats.jpg" width="320" alt="错题统计"><br><sub><b>统计</b> · 错因 / 难度 / 学科分布与薄弱知识点</sub></td>
<td align="center"><img src="docs/screenshots/mindmap.jpg" width="320" alt="思维导图"><br><sub><b>导图</b> · 按学科生成知识体系思维导图</sub></td>
<td align="center"><img src="docs/screenshots/weekly-report.jpg" width="320" alt="学习周报"><br><sub><b>周报</b> · AI 按本周数据出诊断与下周建议</sub></td>
</tr>
</table>

### AI 对话：哪题不会问哪题

<table>
<tr>
<td align="center"><img src="docs/screenshots/chat-session.jpg" width="320" alt="对话与公式"><br><sub><b>对话</b> · 流式回答，公式逐条排版</sub></td>
<td align="center"><img src="docs/screenshots/chat-context.jpg" width="320" alt="题目上下文"><br><sub><b>上下文</b> · 带着题目、学科、错因提问</sub></td>
<td align="center"><img src="docs/screenshots/chat-drawer.jpg" width="320" alt="历史对话抽屉"><br><sub><b>历史</b> · 抽屉式列表，左滑置顶 / 重命名 / 归档</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/temp-session.jpg" width="320" alt="临时对话"><br><sub><b>临时对话</b> · 退出即归档，可再找回</sub></td>
<td align="center"><img src="docs/screenshots/memory-doc.jpg" width="320" alt="记忆库"><br><sub><b>记忆库</b> · 整篇分节文档，每轮对话自动带上</sub></td>
<td align="center"><img src="docs/screenshots/library-index.jpg" width="320" alt="资料库"><br><sub><b>资料库</b> · 教材讲义入库，向量化后语义检索</sub></td>
</tr>
</table>

### 背单词：考法随机出题，答错自动回收

<table>
<tr>
<td align="center"><img src="docs/screenshots/word-study.jpg" width="320" alt="背单词刷题"><br><sub><b>刷题</b> · 四选一 / 卡片 / 释义 / 拼写轮着来</sub></td>
<td align="center"><img src="docs/screenshots/word-search.jpg" width="320" alt="词库检索"><br><sub><b>词库</b> · 4356 考研词，中英文与释义都能搜</sub></td>
<td align="center"><img src="docs/screenshots/word-stats.jpg" width="320" alt="背单词统计"><br><sub><b>统计</b> · 词库进度 / 连续打卡 / 月度热力图</sub></td>
</tr>
</table>

### 番茄钟与成就：专注有数，里程碑可见

<table>
<tr>
<td align="center"><img src="docs/screenshots/pomodoro-timer.jpg" width="320" alt="番茄钟计时"><br><sub><b>计时</b> · 按学科记每次专注，可自定义时长</sub></td>
<td align="center"><img src="docs/screenshots/pomodoro-stats.jpg" width="320" alt="番茄钟统计"><br><sub><b>统计</b> · 今日 / 近 7 天 / 近 30 天趋势</sub></td>
<td align="center"><img src="docs/screenshots/badge-wall.jpg" width="320" alt="成就墙"><br><sub><b>成就墙</b> · 100 枚徽章，11 个类别</sub></td>
</tr>
</table>

### 设置：Key、上下文与数据都在自己手里

<table>
<tr>
<td align="center"><img src="docs/screenshots/settings.jpg" width="320" alt="设置总览"><br><sub><b>设置</b> · AI 与识别 / 学习 / 数据 / 通用 四组折叠</sub></td>
<td align="center"><img src="docs/screenshots/ai-ocr.jpg" width="320" alt="OCR 配置"><br><sub><b>识别</b> · MinerU 或任意 OpenAI 兼容视觉模型</sub></td>
<td align="center"><img src="docs/screenshots/ai-llm.jpg" width="320" alt="大模型配置"><br><sub><b>大模型</b> · 多套配置 / 多把 Key，思考强度可选</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/ai-context.jpg" width="320" alt="上下文与防循环"><br><sub><b>上下文</b> · 自动压缩阈值 + 循环输出保护</sub></td>
<td align="center"><img src="docs/screenshots/archived-items.jpg" width="320" alt="已归档"><br><sub><b>归档</b> · 会话 / 题目 / 任务都能点开查看与恢复</sub></td>
<td align="center"><img src="docs/screenshots/about.jpg" width="320" alt="关于与更新"><br><sub><b>关于</b> · 版本与更新检查，不预置任何 Key</sub></td>
</tr>
</table>

---

## 怎么用起来

1. **下载安装**：[Releases 页面](https://github.com/ausyeah/ascensalis/releases/latest)拿最新 APK，直接装（覆盖安装不丢数据）。
2. **想用完整功能就配 Key**（设置 → AI 与识别）：
   - **OCR 渠道**（题目识别）：[MinerU](https://mineru.net) 官方渠道，或任意 OpenAI 兼容的视觉模型（DeepSeek OCR、商汤日日新、Qwen-VL 等）；
   - **大模型**（AI 整理和讲解）：任何 OpenAI 兼容接口都行，支持**一个地址下多把 Key 自动换切**，填 Base URL + Key + 模型名；
   - **联网搜索**（可选）：Tavily 或 DeepSeek 搜索；
   - **资料库向量化**（可选）：任意 OpenAI 兼容 embeddings 接口。
3. **国内服务直连**：内置网络路由——MinerU、商汤等国内供应商自动直连不走代理，长期挂 VPN 也不影响识别。
4. **不想配也能用**：手动录入错题、背单词、番茄钟，全部本地可用。

> 所有 Key 都只存在本机加密存储（EncryptedSharedPreferences），卸载即彻底消失。

---

## 给开发者

**技术栈**：Kotlin + Jetpack Compose + Room + DataStore；公式渲染用 KaTeX（WebView 光栅化）；大模型走 SSE 流式；发布走 GitHub Actions 签名构建。

**模块地图**：

```
app/src/main/java/com/mistakebook/
├── data/        # 数据库、偏好存储、备份、资料库索引
├── net/         # OCR / 大模型 / 嵌入 / 搜索客户端，网络路由
├── pipeline/    # 识别流水线、LaTeX 修复
├── math/        # 公式渲染（KaTeX + 位图裁切）
├── pomodoro/    # 番茄钟与统计
├── wordbook/    # 背单词
├── print/       # A4 导出排版
└── ui/          # 各功能界面（Compose）
```

**本地构建与测试**：

```bash
./gradlew assembleDebug        # 构建 Debug 包
./gradlew testDebugUnitTest    # 跑单元测试（1700+ 用例）
./gradlew assembleRelease      # 构建正式签名包（需 local.properties 配好签名信息）
```

**测试说明**：渲染链路有契约测试守着——`MathHtmlContractTest` 会检查公式渲染函数不允许被改成异步形式（历史上这么改出过全量渲染失败的故障，见提交记录里的 `P0 热修复`）；左滑手势、识别流水线的 Key 读取等行为契约也各有专属测试钉着。

**项目文档**（`docs/`）：

| 文档 | 内容 |
|---|---|
| [CHANGELOG.md](docs/CHANGELOG.md) | 逐版本更新记录，与 GitHub Releases 同步 |
| [HANDOFF.md](docs/HANDOFF.md) | 接手本项目的完整上下文（当前状态、硬性规则、常见坑） |
| [DECISIONS.md](docs/DECISIONS.md) | 架构与功能的设计决策及其取舍理由 |
| [OPTIMIZATION_NOTES.md](docs/OPTIMIZATION_NOTES.md) | 性能优化任务的动机、测量数据与取舍 |
| [RENDER-TEST-PROMPT.md](docs/RENDER-TEST-PROMPT.md) | 渲染链路的实测提示词与验收表 |

---

## 数据与安全

- **不预置任何 API Key**：公开安装包里没有任何人的密钥，Key 由使用者自己填；
- **Key 只存本机**：加密存储，不进日志、不随备份外泄；
- **仓库里没有密钥文件**：签名密钥本体只在维护者本地与 CI Secrets 里，仓库内只有占位模板；
- **升级不丢数据**：正式签名发布，新旧版本直接覆盖安装；
- **记忆与资料只在本机**：记忆文档、资料库与其向量索引存在本地，随卸载一并消失，不上传任何服务器。

## 许可证

[MIT](LICENSE)。随包分发的第三方组件（KaTeX 及其字体，MIT）见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 免责声明

本项目仅提供客户端能力。OCR 服务（MinerU 等）与大模型 API 均为第三方服务，账号注册、配额与费用由使用者自行承担；请在使用前阅读并遵守相应服务条款。
