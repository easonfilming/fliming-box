# ============================================================
#  菲林档案 —— 工具链安装（自包含，不写系统目录，不需要管理员）
#  解压已下载的 JDK 与 Android 命令行工具，再拉取 platform + build-tools
#
#  注意：脚本里所有原生命令都不加 2>&1。
#  PS 5.1 下 2>&1 会把 stderr 包成 ErrorRecord，配合 Stop 直接终止。
# ============================================================
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = $PSScriptRoot
$tc   = "$root\toolchain"
$sdk  = "$tc\android-sdk"

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Unzip($zip, $dest) {
    # Expand-Archive 对大包极慢，直接用 .NET
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
}

# ---------- 1. 解压 JDK ----------
if (-not (Test-Path "$tc\jdk-17")) {
    Write-Output "=== 解压 JDK 17 ==="
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Unzip "$tc\jdk17.zip" "$tc\jdk-tmp"
    $inner = Get-ChildItem "$tc\jdk-tmp" -Directory | Select-Object -First 1
    Move-Item $inner.FullName "$tc\jdk-17"
    Remove-Item "$tc\jdk-tmp" -Recurse -Force
    Write-Output ("  {0}s" -f [math]::Round($sw.Elapsed.TotalSeconds, 1))
}
$jdk = "$tc\jdk-17"
if (-not (Test-Path "$jdk\bin\javac.exe")) { throw "JDK 解压异常，找不到 javac: $jdk\bin\javac.exe" }
$env:JAVA_HOME = $jdk
Write-Output "JAVA_HOME = $jdk"
& "$jdk\bin\java.exe" -version

# ---------- 2. 解压 cmdline-tools ----------
$clBin = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $clBin)) {
    Write-Output ""
    Write-Output "=== 解压 Android cmdline-tools ==="
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    Unzip "$tc\cmdline-tools.zip" "$sdk\cmdline-tools\tmp"
    Move-Item "$sdk\cmdline-tools\tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
    Remove-Item "$sdk\cmdline-tools\tmp" -Recurse -Force
}
if (-not (Test-Path $clBin)) { throw "cmdline-tools 解压异常: $clBin" }
Write-Output "sdkmanager = $clBin"

# ---------- 3. 接受许可 ----------
# PowerShell 往 .bat 里管道输入 stdin 不可靠（cmd 会截走），
# 所以写一个全是 y 的文件，交给 cmd.exe 做输入重定向。
Write-Output ""
Write-Output "=== 接受 SDK 许可 ==="
$yesFile = "$sdk\yes.txt"
New-Item -ItemType Directory -Force $sdk | Out-Null
[System.IO.File]::WriteAllText($yesFile, ("y`r`n" * 80), [System.Text.Encoding]::ASCII)
$licLine = '"' + $clBin + '" --sdk_root="' + $sdk + '" --licenses < "' + $yesFile + '"'
cmd.exe /c $licLine | Out-Null
Remove-Item $yesFile -Force -ErrorAction SilentlyContinue

$licDir = "$sdk\licenses"
if (Test-Path $licDir) {
    Write-Output ("  已生成许可文件: " + ((Get-ChildItem $licDir).Name -join ', '))
} else {
    Write-Output "  警告：没有生成 licenses 目录"
}

# ---------- 4. 安装 platform 与 build-tools ----------
Write-Output ""
Write-Output "=== 安装 platforms;android-34 与 build-tools;34.0.0 ==="
& $clBin --sdk_root="$sdk" "platforms;android-34" "build-tools;34.0.0" | Select-Object -Last 8

# ---------- 5. 核对 ----------
Write-Output ""
Write-Output "=== 核对 ==="
$checks = [ordered]@{
    'javac'       = "$jdk\bin\javac.exe"
    'keytool'     = "$jdk\bin\keytool.exe"
    'aapt2'       = "$sdk\build-tools\34.0.0\aapt2.exe"
    'd8'          = "$sdk\build-tools\34.0.0\d8.bat"
    'zipalign'    = "$sdk\build-tools\34.0.0\zipalign.exe"
    'apksigner'   = "$sdk\build-tools\34.0.0\apksigner.bat"
    'android.jar' = "$sdk\platforms\android-34\android.jar"
}
$bad = 0
foreach ($k in $checks.Keys) {
    if (Test-Path $checks[$k]) { Write-Output ("  OK    {0,-12}" -f $k) }
    else { Write-Output ("  MISS  {0,-12} {1}" -f $k, $checks[$k]); $bad++ }
}
Write-Output ""
if ($bad) { Write-Output "有 $bad 项缺失，构建会失败。" } else { Write-Output "工具链就绪。" }
Get-PSDrive C | Select-Object @{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}} | Format-Table -AutoSize
