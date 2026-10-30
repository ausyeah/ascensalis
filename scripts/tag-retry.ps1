# 打 tag 并推送（带代理 + 重试）。v0.0.78 交接新增。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\tag-retry.ps1 -Tag v0.0.79
#
# 之后等 tag 触发的 CI：GitHub Actions 会构建签名 APK 并挂到 Release。
param(
    [Parameter(Mandatory = $true)][string]$Tag
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$env:GIT_TERMINAL_PROMPT = '0'

# tag 已存在时先删（本地重建），已推到远端则跳过删除报错
git tag -d $Tag 2>$null | Out-Null
git tag -a $Tag -m $Tag
if ($LASTEXITCODE -ne 0) { throw "创建 tag 失败：$Tag" }

for ($i = 1; $i -le 8; $i++) {
    Write-Host "[tag push attempt $i]"
    git -c http.proxy=http://127.0.0.1:7890 push origin $Tag
    if ($LASTEXITCODE -eq 0) { Write-Host 'TAG_PUSH_OK'; exit 0 }
    Start-Sleep -Seconds 8
}
Write-Host 'TAG_PUSH_FAILED'
exit 1
