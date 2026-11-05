# 自动修复任务书（由 scripts/autopilot.ps1 渲染后交给 ZCode agent）

你在一个**独立 worktree** 里工作：`{{WORKTREE}}`（仓库 `{{REPO}}`，基底分支 `{{BASE_BRANCH}}`）。
当前分支已经切好：`{{BRANCH}}`。你的唯一任务是修复下面这个 issue。

---

## Issue #{{ISSUE_NUMBER}}：{{ISSUE_TITLE}}

{{ISSUE_BODY}}

---

## 必须遵守的仓库规则（违反即返工，PR 会被打回）

1. **不引入任何新依赖**。现有依赖见 `app/build.gradle.kts` 与 `gradle/libs.versions.toml`。
2. **界面文案全部进 `app/src/main/res/values/strings.xml`**，代码里不写中文字符串字面量。
3. **每个行为修复必须配 JVM 单元测试**（`app/src/test/java/...`），测试要钉住行为而不是实现细节。
4. **不新增 TODO / FIXME**。发现旧的就顺手删掉也可以，但不许新增。
5. **提交信息中文**，前缀用 `fix:` / `refactor:` / `test:` / `feat:` / `chore:`。
6. **版本号改动单独一个 commit，且只动 `app/build.gradle.kts` 里 `versionCode` / `versionName` 两行**。
   同时把 `versionCode` 上面那行长注释里追加一段 `, N = vX.Y.Z`（该行是版本号→code 的对照表，必须同步）。
7. 如果改动碰到了被 contract test 钉住的字面量（文案、颜色、尺寸），**同步改测试**。
8. **不要 push 到 main**，只 push `{{BRANCH}}` 分支。
9. **不要合并 PR**——CI 会在检查通过后自动合并。

## 工作步骤

### 1. 读懂问题

- 读 issue 正文。如果正文里有截图链接（`https://github.com/user-attachments/assets/...`），
  用 `curl -L -o /tmp/issue.png "<url>"` 下载后用图片查看工具看，**不要靠猜**。
- 在代码里定位。先 `rg` 找相关文案/组件，再读文件。

### 2. 修

- 改动尽量小，只解决 issue 描述的问题。
- 如果是 UI 细节问题，注意和同类页面的风格一致（参见 `app/src/main/java/com/mistakebook/ui/`）。

### 3. 验证

Windows 上跑单测（**必须真跑**，不许跳过）：

```powershell
cd {{WORKTREE}}
.\gradlew.bat :app:localTest --offline --no-configuration-cache -I .localtest.init.gradle.kts
```

已知基线：**有 13 个 Paparazzi/layoutlib 相关的固定失败**（截图渲染测试在本机跑不了）。
只要**没有新增失败**就算通过。跑完把总用例数和失败列表贴进 PR 正文。

### 4. 版本号（单独一个 commit）

读当前 `versionCode` 与 `versionName`，各自 +1（patch 位）。例如 `120 / "0.0.120"` → `121 / "0.0.121"`。

```bash
git add app/build.gradle.kts
git commit -m "chore: 版本号升至 0.0.121"
```

### 5. 推分支 + 开 PR

```bash
git push -u origin {{BRANCH}}
```

本机**没有 `gh` CLI**，用 REST API（`GH_TOKEN` 已在环境变量里）：

```bash
curl -s -X POST \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  "https://api.github.com/repos/{{REPO}}/pulls" \
  -d '{"title":"fix: <一句话说明>","head":"{{BRANCH}}","base":"{{BASE_BRANCH}}","body":"Closes #{{ISSUE_NUMBER}}\n\n<改动说明 + 单测结果>"}'
```

拿到返回的 `number` 后给 PR 打 `autopilot` 标签，自动合并才会生效：

```bash
curl -s -X POST \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/{{REPO}}/issues/<PR_NUMBER>/labels" \
  -d '{"labels":["autopilot"]}'
```

### 6. 收尾

- 在 issue 里留言：改了什么、单测结果、PR 编号。
- **不要自己关 issue**，`finalize` job 会在 APK 发布后关。

## 失败时怎么办

- 定位不到问题、或修了但测试仍红：**不要硬提 PR**。在 issue 里留言说明卡在哪、贴关键日志，
  然后 `exit 1` 结束。watcher 会移除 `autopilot-active` 标签，让人工接手。
- 绝对不要为了让测试变绿而删除或跳过既有测试。