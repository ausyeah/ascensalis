<#
.SYNOPSIS
  issue 自动修复 watcher（C 段：负责「拉起 ZCode agent 修代码」，A 段由
  .github/workflows/issue-autopilot.yml 负责「CI 合并 / 出包 / 关 issue」）。

.DESCRIPTION
  轮询 GitHub 上打了 `autopilot` 标签的 open issue，串行地为每一个：
    打 autopilot-active 标签 → 建独立 worktree → 拉起无头 ZCode agent
    → agent 在 worktree 里修代码 / 跑单测 / bump 版本 / 推分支 / 开 PR
  之后的事交给 CI：PR 检查绿 → 自动合并 → 打 tag → 出签名 APK → 关 issue。

  三条约束写死在脚本里：
    1. 一次只修一个（MaxPerRound 默认 1）：并行修会撞版本号；
    2. 本机没有 gh CLI，全部走 REST API（Invoke-RestMethod）；
    3. agent 在独立 worktree 工作，绝不碰主检出里的未提交改动。

.EXAMPLE
  # 跑一轮就退出（适合计划任务）
  powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -Once

.EXAMPLE
  # 常驻循环，每 15 分钟扫一次
  powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -IntervalMin 15

.EXAMPLE
  # 注册成 Windows 计划任务：开机自启、每 15 分钟跑一轮
  powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -InstallTask

.EXAMPLE
  # 只看这一轮会做什么，不真的拉起 agent
  powershell -ExecutionPolicy Bypass -File scripts\autopilot.ps1 -Once -DryRun
#>
#Requires -Version 5.1
param(
    [switch]$Once,
    [switch]$DryRun,
    [switch]$InstallTask,
    [int]$IntervalMin = 15,
    [int]$MaxPerRound = 1,
    [string]$BaseBranch = "main",
    [string]$TaskName = "Ascensalis Autopilot"
)
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $RepoRoot

# ---------------------------------------------------------------- 基础工具

function Write-Step { param([string]$Text) Write-Host "[autopilot] $Text" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host "  ✓ $Text" -ForegroundColor Green }
function Write-Warn2{ param([string]$Text) Write-Host "  ! $Text" -ForegroundColor Yellow }

# git 往 stderr 写「Preparing worktree…」这类正常进度，而 $ErrorActionPreference='Stop'
# 会把它当错误直接抛出。这里临时降级、吃掉输出，只看退出码。
function Invoke-GitQuiet {
    param([string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $null = & git @Arguments 2>&1
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Get-GitHubRepo {
    $url = git remote get-url origin
    if ($url -notmatch 'github\.com(?::\d+)?[:/](.+?)(?:\.git)?/?$') {
        throw "无法从 origin 解析 GitHub 仓库：$url"
    }
    return $Matches[1]
}

function New-ApiHeaders {
    $Token = $env:GH_TOKEN
    if (-not $Token) { $Token = $env:GITHUB_TOKEN }
    if (-not $Token) { throw "缺少 GH_TOKEN / GITHUB_TOKEN 环境变量" }
    return @{
        "Authorization" = "Bearer $Token"
        "Accept"        = "application/vnd.github+json"
        "User-Agent"    = "ascensalis-autopilot"
    }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        $Body = $null
    )
    # 不叫 $args：那是 PowerShell 的自动变量，赋了容易踩到意外行为
    $req = @{
        Uri        = "https://api.github.com/repos/$script:Repo$Path"
        Headers    = $script:Headers
        Method     = $Method
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $req["Body"]        = ($Body | ConvertTo-Json -Depth 10 -Compress)
        $req["ContentType"] = "application/json; charset=utf-8"
    }
    return Invoke-RestMethod @req
}

function Add-IssueComment {
    param([int]$Number, [string]$Text)
    if ($DryRun) { Write-Warn2 "(dry-run) 评论 #$Number：$($Text.Substring(0, [Math]::Min(60, $Text.Length)))…"; return }
    Invoke-Api -Method POST -Path "/issues/$Number/comments" -Body @{ body = $Text } | Out-Null
}

function Set-IssueLabels {
    param([int]$Number, [string[]]$Add = @(), [string[]]$Remove = @())
    if ($DryRun) { Write-Warn2 "(dry-run) 标签 #$Number +[$($Add -join ',')] -[$($Remove -join ',')]"; return }
    foreach ($L in $Add)    { Invoke-Api -Method POST   -Path "/issues/$Number/labels" -Body @{ labels = @($L) } | Out-Null }
    foreach ($L in $Remove) { Invoke-Api -Method DELETE -Path "/issues/$Number/labels/$([uri]::EscapeDataString($L))" | Out-Null }
}

# ---------------------------------------------------------------- 单轮逻辑

function Get-CandidateIssues {
    # 只认打了 autopilot 的 open issue，且排除 PR（issue 列表里会混进 PR）
    $items = Invoke-Api -Method GET -Path "/issues?state=open&labels=autopilot&sort=created&direction=asc&per_page=50"
    return $items | Where-Object {
        -not $_.pull_request -and
        -not ($_.labels | Where-Object { $_.name -eq 'autopilot-active' })
    }
}

function Resolve-ZCodeCli {
    # 优先用环境变量覆盖，其次用 ZCode 安装目录里打包的 CLI
    if ($env:ZCODE_CLI -and (Test-Path $env:ZCODE_CLI)) { return @{ Node = (Get-Command node -ErrorAction SilentlyContinue).Source; Cli = $env:ZCODE_CLI } }
    $bundled = "C:\Program Files\ZCode\resources\glm\zcode.cjs"
    if (-not (Test-Path $bundled)) { throw "找不到 ZCode CLI（$bundled）。请设置 ZCODE_CLI 环境变量指向 zcode.cjs" }
    $node = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $node) { $node = "C:\Users\$env:USERNAME\AppData\Local\node\node.exe" }
    if (-not (Test-Path $node)) { throw "找不到 node。请安装 node 或把 node 加进 PATH" }
    return @{ Node = $node; Cli = $bundled }
}

function New-IssueWorktree {
    param([int]$Number)
    $branch = "fix/issue-$Number"
    $wt = Join-Path (Split-Path $RepoRoot -Parent) "autopilot-wt\issue-$Number"

    Invoke-GitQuiet @("fetch", "origin", $BaseBranch, "--quiet") | Out-Null
    if ((Invoke-GitQuiet @("rev-parse", "--verify", "--quiet", $branch)) -eq 0) {
        # 本地已有同名分支（上轮失败留下的），先删掉重建，保证从最新 main 起
        Invoke-GitQuiet @("branch", "-D", $branch) | Out-Null
    }
    if (Test-Path $wt) {
        Invoke-GitQuiet @("worktree", "remove", "--force", $wt) | Out-Null
        Remove-Item -Recurse -Force $wt -ErrorAction SilentlyContinue
    }
    Invoke-GitQuiet @("worktree", "prune") | Out-Null
    $code = Invoke-GitQuiet @("worktree", "add", "-b", $branch, $wt, "origin/$BaseBranch")
    if ($code -ne 0 -or -not (Test-Path $wt)) { throw "建 worktree 失败：$wt（exit=$code）" }

    # worktree 是新目录，gitignore 的本地配置不会跟过来，得手工复制
    foreach ($f in @("local.properties", "keystore.properties")) {
        $src = Join-Path $RepoRoot $f
        if (Test-Path $src) { Copy-Item $src (Join-Path $wt $f) -Force }
    }
    return $wt
}

function Render-Brief {
    param([int]$Number, [string]$Title, [string]$Body, [string]$Branch, [string]$Worktree)
    $tpl = Get-Content (Join-Path $PSScriptRoot "autopilot-prompt.md") -Raw -Encoding UTF8
    $tpl = $tpl.Replace("{{ISSUE_NUMBER}}", "$Number")
    $tpl = $tpl.Replace("{{ISSUE_TITLE}}", $Title)
    $tpl = $tpl.Replace("{{ISSUE_BODY}}", $Body)
    $tpl = $tpl.Replace("{{BRANCH}}", $Branch)
    $tpl = $tpl.Replace("{{WORKTREE}}", $Worktree)
    $tpl = $tpl.Replace("{{REPO}}", $script:Repo)
    $tpl = $tpl.Replace("{{BASE_BRANCH}}", $BaseBranch)
    # 任务书写在临时目录而不是 worktree 里，免得被 agent 误提交
    $dir = Join-Path $env:TEMP "ascensalis-autopilot"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $path = Join-Path $dir "issue-$Number.md"
    Set-Content -Path $path -Value $tpl -Encoding UTF8
    return $path
}

function Invoke-AgentOnIssue {
    param($Issue)

    $num    = [int]$Issue.number
    $title  = [string]$Issue.title
    $body   = if ($Issue.body) { [string]$Issue.body } else { "(正文为空)" }
    $branch = "fix/issue-$num"

    Write-Step "处理 issue #$num：$title"

    if (-not $DryRun) {
        Set-IssueLabels -Number $num -Add @("autopilot-active")
        Add-IssueComment -Number $num -Text "🤖 本地 agent 已启动，正在修复。`n`n- 分支：``$branch```n- 预计十几分钟内会提 PR"
    }

    $wt = New-IssueWorktree -Number $num
    Write-Ok "worktree：$wt"

    $brief = Render-Brief -Number $num -Title $title -Body $body -Branch $branch -Worktree $wt
    Write-Ok "任务书：$brief"

    if ($DryRun) {
        Write-Warn2 "(dry-run) 跳过 agent"
        Invoke-GitQuiet @("worktree", "remove", "--force", $wt) | Out-Null
        Invoke-GitQuiet @("branch", "-D", $branch) | Out-Null
        return $true
    }

    $cli = Resolve-ZCodeCli
    $logDir = Join-Path $env:TEMP "ascensalis-autopilot"
    $log = Join-Path $logDir ("issue-{0}-{1:yyyyMMdd-HHmmss}.log" -f $num, (Get-Date))

    # 任务书很长（含 issue 正文），命令行有长度上限，所以只传一句「去读那个文件」
    $prompt = "你在执行自动化修复任务。工作目录是 $wt 。先完整读取任务书 $brief ，然后严格按其中的步骤执行：定位问题、修改代码、写单元测试、跑 localTest、单独提交版本号、推送分支 $branch 、用 REST API 开 PR 并打 autopilot 标签。全程不要询问任何人，按任务书自行决策。"

    Write-Step "拉起 ZCode agent（日志：$log）"
    $env:GH_TOKEN = if ($env:GH_TOKEN) { $env:GH_TOKEN } else { $env:GITHUB_TOKEN }

    Push-Location $wt
    $prevEap = $ErrorActionPreference
    # agent 会把进度写到 stderr，Stop 偏好会把它当异常抛出来，这里临时降级
    $ErrorActionPreference = 'Continue'
    try {
        & $cli.Node $cli.Cli -p $prompt --cwd $wt --mode yolo --surface terminal --no-color 2>&1 |
            Tee-Object -FilePath $log
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
        Pop-Location
    }

    # 判断 agent 是否真的交付了：分支推上去了吗？
    $pushed = $false
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $head = (& git ls-remote --heads origin $branch 2>&1 | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($head -match "refs/heads/$([regex]::Escape($branch))") { $pushed = $true }

    if ($exit -eq 0 -and $pushed) {
        Write-Ok "agent 完成，分支 $branch 已推送（日志：$log）"
        return $true
    }

    Write-Warn2 "agent 未完成（exit=$exit, pushed=$pushed）"
    # 日志末尾用四空格缩进代码块贴出（不用反引号围栏：here-string 里反引号会被转义）
    $tail = (Get-Content $log -Tail 20 -ErrorAction SilentlyContinue) -join "`n"
    $tailIndented = ($tail -split "`n" | ForEach-Object { "    $_" }) -join "`n"
    $msg = "⚠️ 自动修复未完成（agent 退出码 $exit，分支已推送：$pushed）。`n`n" +
           "已解除 autopilot-active 标签，可以重新 workflow_dispatch 入队再试一次，或人工接手。`n`n" +
           "日志末尾：`n`n" + $tailIndented
    Add-IssueComment -Number $num -Text $msg
    Set-IssueLabels -Number $num -Remove @("autopilot-active")
    return $false
}

function Invoke-Round {
    # 用 @() 包一层：只有一个 issue 时 Where-Object 返回的是标量，.Count 会取不到
    $issues = @(Get-CandidateIssues)
    if ($issues.Length -eq 0) { Write-Step "没有待处理的 autopilot issue"; return 0 }

    Write-Step "发现 $($issues.Length) 个待处理 issue，本轮处理 $MaxPerRound 个"
    $done = 0
    foreach ($it in $issues) {
        if ($done -ge $MaxPerRound) { break }
        try {
            Invoke-AgentOnIssue -Issue $it | Out-Null
        } catch {
            Write-Warn2 "处理 #$($it.number) 时异常：$($_.Exception.Message)"
            try {
                Add-IssueComment -Number ([int]$it.number) -Text "⚠️ 自动修复流水线异常：$($_.Exception.Message)"
                Set-IssueLabels -Number ([int]$it.number) -Remove @("autopilot-active")
            } catch { }
        } finally {
            $done++
        }
    }
    return $done
}

# ---------------------------------------------------------------- 入口

$script:Repo    = Get-GitHubRepo
$script:Headers = New-ApiHeaders

if ($InstallTask) {
    # 计划任务每次只跑一轮就退出（-Once）：常驻进程容易被系统休眠挂死。
    # 用 schtasks 而不是 Register-ScheduledTask：Minute 触发的 API 组合在不同
    # Windows 版本上对 -RepetitionDuration 的要求不一致，schtasks 更稳。
    $ps = (Get-Command powershell.exe).Source
    $argLine = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" -Once"
    & schtasks.exe /Create /TN "$TaskName" /TR "`"$ps`" $argLine" /SC MINUTE /MO $IntervalMin /F
    if ($LASTEXITCODE -ne 0) { throw "schtasks 注册失败（exit=$LASTEXITCODE）" }

    # schtasks 默认「电池模式不启动、切到电池就停」，笔记本拔电后流水线会静默失效。
    # 这里补两条：来电补跑、不因电池停止。改不动也不致命，提示一下即可。
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        $task.Settings.DisallowStartIfOnBatteries = $false
        $task.Settings.StopIfGoingOnBatteries     = $false
        $task.Settings.StartWhenAvailable         = $true
        $task.Settings.ExecutionTimeLimit         = "PT2H"
        Set-ScheduledTask -TaskName $TaskName -Settings $task.Settings -ErrorAction Stop | Out-Null
    } catch {
        Write-Warn2 "电源策略未能调整（$($_.Exception.Message)），电池模式下该任务可能不启动"
    }
    Write-Ok "已注册计划任务「$TaskName」：每 $IntervalMin 分钟跑一轮（-Once 模式，跑完即退）"
    exit 0
}

# 进程锁：计划任务与手动运行撞车时，后来者直接退出
$lockPath = Join-Path $env:TEMP "ascensalis-autopilot.lock"
try {
    $lock = [System.IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch {
    Write-Warn2 "已有一轮在跑，本轮跳过"
    exit 0
}

try {
    if ($Once) {
        Invoke-Round | Out-Null
    } else {
        Write-Step "常驻模式，每 $IntervalMin 分钟一轮（Ctrl+C 退出）"
        while ($true) {
            try { Invoke-Round | Out-Null } catch { Write-Warn2 $_.Exception.Message }
            Start-Sleep -Seconds ($IntervalMin * 60)
        }
    }
} finally {
    $lock.Dispose()
}