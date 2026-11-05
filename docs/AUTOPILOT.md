# Issue 自动修复流水线（Autopilot）

给 issue 打一个 `autopilot` 标签，剩下的交给流水线：**修代码 → 跑测试 → 提 PR → CI 绿后自动合并 → 打 tag 出签名 APK → 回填下载链接 → 关闭 issue**。

分成两段，各管各的：

| 段 | 位置 | 职责 | 为什么这样分 |
|---|---|---|---|
| **A** | `.github/workflows/issue-autopilot.yml` | 认领、CI 绿后合并、打 tag、出包、关 issue、停滞巡检 | 确定性动作，放 CI 最稳、可审计 |
| **C** | `scripts/autopilot.ps1` + `scripts/autopilot-prompt.md` | 轮询 issue、拉起 ZCode agent 真改代码 | 只有本机有 Android SDK / 模拟器 / 真实 Key，CI runner 跑不了 |

```
你打 autopilot 标签
   │
   ├─→ [A] claim job：加 autopilot-active + 回执
   │
   └─→ [C] 本机 watcher（每 15 分钟）
          ├─ 建独立 worktree：../autopilot-wt/issue-N  （不动你主检出的未提交改动）
          ├─ 渲染任务书 → %TEMP%/ascensalis-autopilot/issue-N.md
          ├─ 拉起无头 agent：zcode -p "读那份任务书" --cwd <worktree> --mode yolo
          └─ agent：改代码 + 写单测 + 跑 localTest + bump 版本 + push 分支 + 开 PR + 打 autopilot
                 │
                 └─→ [A] merge job：gh pr checks --watch 通过 → gh pr merge --merge
                        │
                        └─→ [A] finalize job
                               ├─ 读 app/build.gradle.kts 的版本号，校验单调递增
                               ├─ 打 tag 并显式派发 android.yml（GITHUB_TOKEN 推 tag 不会自动触发，见下）
                               ├─ 等 android.yml 出正式签名 APK
                               └─ 回填下载链接 + 关闭 issue + 换 autopilot-done 标签
```

## 一次性配置

### 1. 标签（已建好）

`autopilot`（入队）→ `autopilot-active`（处理中）→ `autopilot-done`（已发布）。

### 2. `android.yml` 的派发（已内置，无需配置）

GitHub 有条规则：**由 `GITHUB_TOKEN` 触发的事件不会再启动新的工作流**。所以 `finalize` 用 `GITHUB_TOKEN` 推 tag 之后，`android.yml` 不会自己跑起来——结果就是「tag 有了、APK 没有」。

解决办法不是配 PAT，而是推完 tag 后显式派发一次：

```bash
gh workflow run android.yml --repo <repo> --ref "v0.0.121"
```

`--ref` 指到 tag，`workflow_dispatch` 运行时 `github.ref` 就是 `refs/tags/v0.0.121`，
`android.yml` 里 `startsWith(github.ref, 'refs/tags/v')` 的发布分支判定照样成立，APK 正常挂 Release。

→ 需要给 `issue-autopilot.yml` 开 `actions: write` 权限（已写在文件里）。

### 3. 本机 watcher（Windows 计划任务）

```powershell
# 注册：每 15 分钟一轮，开机登录后自动可用
powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -InstallTask

# 先看它准备干什么，不真跑
powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -Once -DryRun

# 只跑一轮（调试用）
powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -Once

# 卸载
schtasks /Delete /TN "Ascensalis Autopilot" /F
```

需要两个环境变量（计划任务会用当前用户的，配到「用户环境变量」里才生效）：

- `GH_TOKEN`：推送分支 / 开 PR / 打标签用，需要 `repo` scope
- `ZCODE_CLI`（可选）：默认用 `C:\Program Files\ZCode\resources\glm\zcode.cjs`

## 安全阀（已经写死，别轻易拆）

| 机制 | 位置 | 作用 |
|---|---|---|
| 只认 `autopilot` 标签 | `Get-CandidateIssues` | 没打标签的 issue 一律不碰 |
| 一次只修一个 | `-MaxPerRound`（默认 1） | 并行修会撞版本号 |
| 独立 worktree | `New-IssueWorktree` | 绝不碰主检出的未提交改动 |
| 进程锁 | `%TEMP%\ascensalis-autopilot.lock` | 计划任务与手跑撞车时后来者直接退出 |
| CI 绿才合并 | `merge` job | `gh pr checks --watch` 失败就保持 PR 打开 |
| 版本号单调递增 | `finalize` 的校验步骤 | agent 忘了 bump 或改回去，tag 打不出来 |
| 出包成功才关 issue | `finalize` 的轮询 | 关早了等于没修 |
| 停滞巡检 | `watchdog` job | 8 小时没进展且没有 open PR → 在 issue 里提醒 |
| agent 失败不硬提 PR | 任务书第 6 节 | 卡住了就在 issue 里留言说明，交回人工 |

## 日常用法

**自动**：给任意 issue 打 `autopilot` 标签，等十来分钟收通知。

**手动入队**：Actions → Issue Autopilot → Run workflow → 填 issue 编号。

**只修不想发版**：不打 `autopilot` 标签，直接人工处理。

**中止某一轮**：`schtasks /End /TN "Ascensalis Autopilot"`，然后从 issue 上摘掉 `autopilot-active` 和 `autopilot` 标签。

## 已知边界

- watcher 只知道「agent 退出码 0 **且** 分支推上去了」算成功，它判断不了改动是否真的对——对人负责的最后一道闸是 PR review，`merge` job 只是自动点按钮。
- agent 在 worktree 里跑 `localTest`，已知有 13 个 Paparazzi/layoutlib 基线失败，任务书里写明了「不新增失败即可」。
- 单个 issue 的修复上限大约十几分钟到半小时；agent 卡死时同名的分支不会推上去，watcher 会在该轮结束时在 issue 里贴日志并交回人工。