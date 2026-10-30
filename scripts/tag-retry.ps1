# Create and push a tag with proxy + retries (added in v0.0.78 handoff).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\tag-retry.ps1 -Tag v0.0.79
#
# Afterwards the tag-triggered CI builds the signed APK and attaches it to the Release.
# Keep this file ASCII-only: Windows PowerShell 5.1 misreads UTF-8 without BOM.
param(
    [Parameter(Mandatory = $true)][string]$Tag
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$env:GIT_TERMINAL_PROMPT = '0'

if (git tag -l $Tag) { git tag -d $Tag | Out-Null }
git tag -a $Tag -m $Tag
if ($LASTEXITCODE -ne 0) { throw "Failed to create tag: $Tag" }

for ($i = 1; $i -le 8; $i++) {
    Write-Host "[tag push attempt $i]"
    git -c http.proxy=http://127.0.0.1:7890 push origin $Tag --force
    if ($LASTEXITCODE -eq 0) { Write-Host 'TAG_PUSH_OK'; exit 0 }
    Start-Sleep -Seconds 8
}
Write-Host 'TAG_PUSH_FAILED'
exit 1
