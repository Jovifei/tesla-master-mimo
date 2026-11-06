$ErrorActionPreference = "Stop"

$cliPath = "E:\AI_Tools\Other\WeChatDevTools\cli.bat"
$repoRoot = "E:\project\tesla_master\miniprogram_Weixin"
$projectRoot = Join-Path $repoRoot "miniprogram"
$distPath = Join-Path $projectRoot "dist"

$pkg = Get-Content (Join-Path $projectRoot "package.json") -Raw | ConvertFrom-Json
$version = $pkg.version
$head = (git -C $repoRoot rev-parse --short HEAD).Trim()
$desc = "v$version ($head) 全面落地 Apple 风格 UI 重构"

Write-Host "[1/3] 检查微信开发者工具登录状态..."
$loginOutput = & $cliPath islogin 2>&1 | Out-String
if ($loginOutput -notmatch '"login":\s*true') {
    Write-Host "[提示] 微信开发者工具当前未登录，正在调起登录二维码..."
    & $cliPath login -f terminal
}

Write-Host "[2/3] 正在上传代码包至微信后台 (版本: $version, 说明: $desc)..."
& $cliPath upload --project $distPath -v $version -d $desc

Write-Host "[3/3] 上传成功！请前往微信公众平台后台（https://mp.weixin.qq.com）「版本管理」将 $version 设为体验版。"
