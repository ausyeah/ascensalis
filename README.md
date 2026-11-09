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
| **番茄钟** | 番茄倒计时与正向计时两种，按「学科 → 任务」两级记录每次专注；时长轮盘自定，到点自动记录 + 桌面通知；今天学多久、这周什么走势、哪科投入最多，图表一看就知道，还能从别的计时软件导入记录 |
| **复习与周报** | 按遗忘曲线排复习档期，今日该复习哪几道一目了然；AI 还能按薄弱知识点生成复习排期、知识体系思维导图与每周学习周报 |
| **成就墙** | 100 枚徽章覆盖刷题、专注、背单词、对话、资料库与使用习惯，学习里程碑一眼可见 |

**不联网也能用**：错题本支持纯手动录入，背单词、番茄钟完全本地。联网能力（题目识别、AI 讲解、生图）需要填自己的 API Key——Key 只存在本机加密空间里，不上传、不进日志。

---

---

## 功能详解

### 错题本

**录入有四条路**

- **拍题**：相机内闪光灯（自动/开/关）、前后摄切换、连拍批次（一批最多 12 张，右下角缩略图堆叠即「完成」）
- **从相册选择**：一次最多选 12 张，走和拍照完全相同的裁切流水线
- **导入 PDF**：文本型 PDF 直接抽文字，扫描件逐页 OCR；支持页码范围（如 `1-5,8,11-12`），超大的分段处理
- **手动录入**：不配任何 Key 也能用——最坏情况下唯一还能往错题本里加题的路

**识别与核对**

- 识别方式四选一：仅 OCR / 当前模型 / 识图模型 / **自动**（按 MinerU → 当前模型 → 识图模型 依次回落）
- OCR 渠道可以配多套：内置 MinerU 文档级解析，也可以加任意 OpenAI 兼容的视觉模型，渠道名/地址/Key/模型名全自填，可「测试连接」
- 裁切：拖四角手柄框选、**涂鸦遮蔽**不想识别的区域、旋转 90°、三档图像增强（轻度/标准/强力）
- 核对：题干、选项（可增删）、答案、解析、错因、难度、知识点、备注逐条可改，**公式实时渲染预览**，还能回看原始识别文本
- **文本审计**：一键检查公式结构与括号配对，能还原 N 处
- 识别被截断或降级会明确告警，不悄悄吞掉内容

**题库管理**

- 搜索 + 三维筛选（学科 / 掌握度 / 错题本 / 待复习）
- **错题本分组**：新建、重命名、删除（默认本不可删，删本时题目自动移入默认本）
- **左滑**：置顶 / 归档（两步确认，杜绝一次滑动直接归档）/ 删除（可撤销，30 天后才物理清理）
- **右滑**：就地摊开原题图（只占卡片原本的矩形，右侧留一条窄拉手）；点图全屏、双指缩放，点一下退出
- 详情页：掌握开关、编辑、**重新识别**（覆盖题干答案解析，保留掌握状态与备注）、AI 讲解、**举一反三**（生成同源变式题，可一键存成新错题）、生成/删除辅助图

**复习与考试**

- 遗忘曲线档期：1 / 2 / 4 / 7 / 15 天，5 轮毕业（走完一整轮阶梯约 29 天）
- 今日复习页：**先看答案再自评**（会 / 模糊 / 不会），本轮结束给汇总，可「立即重刷未掌握」
- 每日复习提醒可设时间（默认 20:00），到点推送
- **考试倒计时**：高考 / 考研 / 自定义日期与自定义事件；考前排期会算出「还能完整过约 N 轮、日均 N 道」，时间不够自动压缩间隔，应用后写回每道题的复习日期
- 桌面小组件「今日」：待复习数 / 考试倒计时 / 今日专注分钟

**打印与导出**

- 打印页勾选 + 反选 + 长按拖动排序；导出 A4 原生 PDF 或单文件 HTML
- 可选：是否带题目原图、是否带答案与解析、**留白重做模式**（重做区高度按 pt 调）、打印专用字号与字体
- 错题统计弹窗里可导出 **Anki CSV**

**首页图标行的 AI 加餐**

- **错题统计**：全部 / 已掌握 / 本周新增，外加错因分布、难度分布、学科分布、薄弱知识点 Top10
- **知识体系思维导图**：按学科把薄弱知识点（🔴≥3 道 / 🟠2 道）织成体系图，可缩放拖拽
- **AI 学习周报**：按本周数据给诊断与下周建议，可重新生成

### AI 对话

**模型与 Key**

- 多套大模型配置（Base URL / API Key / 模型名）；**一个地址下可挂多把 Key，鉴权失败或额度用尽自动换下一把**
- 一键拉取该配置下所有 Key 可见的模型，合并去重后勾选可用范围、设为主模型、标记该模型是否支持图片
- **思考强度**：关闭 / 低 / 中 / 高 / 最高；供应商字段名不统一时自动换字段重试（`reasoning_effort` / `thinking` / `reasoning`）
- 识图模型、生图渠道各自独立配置；生图渠道用请求体模板，`{prompt}` / `{width}` / `{height}` 占位

**对话里能做什么**

- 流式输出、随时停止、**流式中「打断并发送」**（边生成边插话纠正）
- 附件：拍照 / 相册 / 浏览文件（图片、docx、xlsx、csv、pdf、md、txt）/ **@资料库**；单文件上限 20MB，docx、表格、PDF 会自动提字
- 内置工具：联网搜索、识图、生图、图片 OCR、计算器、资料库（列目录 / 按需读 / 检索）、写入记忆、**反问用户**（给选项 + 倒计时 + 推荐项）
- **专用函数画图**：模型给出表达式与范围，App 自己精确采样画出坐标系图像（坐标轴、刻度、单调性、极值、交点都是真的，渐近线处自动断线）——不是让 AI 看着"像那么回事"地手画，也不用生图模型画插画
- **对话一键存成错题**：说一句「把这题加入错题本」，模型就把这一问一答整理成题干 / 答案 / 解析 / 学科 / 知识点 / 错因 / 难度，直接写进你的错题本（不再依赖手动存备注）
- **内置函数计算器**（抽屉里「绘图生图」旁边）：固定画布 + 坐标轴刻度，填表达式立刻出图，可拖动平移、双指缩放、双击复位，最多四条曲线并存；**不需要模型也不需要 Key**，算完一键存进对话继续问。写法支持三种：显函数 `y=f(x)`、隐函数方程（`x^2+y^2=1`、`x=y^2`）、极坐标（`r=1+cos(θ)`、`r=sin(3θ)`）
- 富文本渲染：公式（LaTeX / MathML）、SVG 示意图、图片，HTML 先清洗再显示
- 思考过程可折叠 / 展开 / 复制
- **长对话自动压缩上下文**：到阈值（默认 50 万 token，可调）自动把前面的对话压成摘要，保留最近 6 条；也可手动点「压缩」
- **循环输出保护**：回答开始打转（尾部连续 4 轮等长重复）自动掐断——正常的「好的好的」这类两次重复不会误杀
- **后台生成**：切到后台继续跑，通知栏常驻「正在生成回复…」
- 消息级操作：重试 / 继续 / 复制 / 删除这条 / **存入题目备注**；失败、已停止、已截断都有明确徽标

**会话管理**

- 历史抽屉按时间分组（今天 / 昨天 / 前天 / N 天前），左滑置顶、重命名、归档，也可删除单条或清空全部
- **临时对话**：退出即归档，可在「设置 → 数据 → 已归档内容 → 已归档会话」找回；往归档会话发消息会自动解档
- 五个快捷动作：**拍题讲解 / 英语翻译 / 作文批改 / 题目批改 / 绘图生图**（没配模型或识别时会直接标出「需配模型」「需配识别」，不会点了没反应）

### 记忆

- 长期记忆是一份**分节文档**（个人背景 / 学习情况 / 偏好…），每一轮对话自动带上，不用每次自我介绍
- 三条写入路径：自己写一句「记住…」、直接编辑全文、模型在对话里主动写入（`save_memory` 工具）
- **整理**：一句话交给模型，把零散记忆重排成清晰的分节条目并生成新版本（带版本号与更新时间）

### 资料库与语义检索

- 支持 md / txt / json / csv / docx / pdf；docx 与 pdf 自动抽文字，原文件保留，二进制文件可只读预览提取出的文本
- 建好向量索引后可以**语义检索**——用中文问，英文资料也能命中；排序是语义 + 关键词融合
- 对话里 @资料库 引用原文，也可以让模型自己列目录、按需读、检索
- 向量化模型与接口独立配置（地址 / Key / 模型名），显示「已索引 N 份资料 · M 块」

### 背单词

- 四种考法轮着来：四选一、卡片翻面、**义项辨析**（专攻熟词僻义）、拼写
- 答错自动进错词本待复习；错词本 / 曾错本 / 熟词本 / 生词本四个子标签
- 连对进度与毕业；单词朗读；一键生成**场景图**（模型写描述 → 生图渠道出图并缓存复用）
- 词库检索：内置 4356 考研词，中英文与释义都能搜，按 生词 / 模糊 / 认识 / 掌握 筛选
- 学习统计：连续打卡、每日新词目标（可自定义）、每日目标提醒、本月打卡热力图、近 7 天学习量与正确率、词库总览进度
- **打卡海报**：可生成，也可用生图背景生成

### 番茄钟

- 番茄倒计时与正向计时两种；时长轮盘自定（小时 / 分钟滚动选择）
- 任务板：**学科 → 任务两级**，新建 / 编辑 / 归档 / 删除；学科与错题本共用一套，改名后录题页同步
- 通知：计时常驻、番茄完成、休息结束、状态（剩余 / 已专注）；到点自动记录、休息循环与休息时长可设
- 统计：目标达成 / 总次数 / 近 7 天 / 近 30 天趋势 / 月度 12 月趋势 / 季度趋势 / 学科分布 / 任务累计 / 单日专注（可前后翻日）
- **从别的计时软件导入**：CSV 或 .xls，自动识别任务分类、任务名、时长，学科不存在就新建
- 手动补记一次专注（只计时长）；专注打卡海报；重置统计（两步确认）

### 成就墙

- **100 枚徽章、11 个分类**：录入 / 掌握 / 复习 / 坚持 / 爆发 / 清零 / 单词 / 专注 / 问答 / 资料 / 收藏，全部纯本地计算
- 已解锁与未解锁分段排列，显示完成度、按分类筛选、进度 N/M 与解锁日期

### 设置：四组里都有什么

| 组 | 内容 |
|---|---|
| **AI 与识别** | 识别方式（四选一）· OCR 多渠道 · 大模型多配置 + 思考强度 · 识图模型 · 生图渠道 · 联网搜索（DeepSeek 或 Tavily，可查额度）· 上下文自动压缩阈值与循环输出保护 · 记忆库 |
| **学习** | 学习工具（资料库、成就墙）· 背单词每日新词上限 · 番茄钟（到点自动记录、休息循环与时长）· 复习提醒时间 · 打印默认项 |
| **数据** | 本机占用与**导出备份（zip）**· 从备份恢复 · 清空回收站 · 已归档内容（题目 / 会话 / 任务三个入口）· 单词书数据重置 · 番茄钟数据重置 |
| **通用** | 主题（跟随系统 / 浅色 / 深色 + 主题色）· 文字与形状（字号 4 档、字体 3 种、圆角 3 档，都带实时预览）· 用量统计（大模型调用与 tokens、OCR 次数与页数，可清零）· 网络与代理 · 关于与更新（检查更新并下载安装、Star、提 Issue） |

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
<td align="center"><img src="docs/screenshots/plot-function.jpg" width="320" alt="函数图像"><br><sub><b>函数图像</b> · 工具精确采样绘制，刻度与极值是真的</sub></td>
<td align="center"><img src="docs/screenshots/function-calculator.jpg" width="320" alt="函数计算器"><br><sub><b>函数计算器</b> · 固定画布，填表达式即出图，可平移缩放</sub></td>
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
- **备份握在你手里**：全部数据可整体导出成 zip、也能从备份恢复；删错的题进 30 天回收站可撤销，归档的题目 / 会话 / 任务单独留着，随时点开查看与恢复；
- **记忆与资料只在本机**：记忆文档、资料库与其向量索引存在本地，随卸载一并消失，不上传任何服务器。

## 许可证

[MIT](LICENSE)。随包分发的第三方组件（KaTeX 及其字体，MIT）见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 免责声明

本项目仅提供客户端能力。OCR 服务（MinerU 等）与大模型 API 均为第三方服务，账号注册、配额与费用由使用者自行承担；请在使用前阅读并遵守相应服务条款。
