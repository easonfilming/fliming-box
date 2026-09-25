# ============================================================
#  菲林档案 —— 构建 APK（Gradle）
#
#  从手工 aapt2 流水线换成 Gradle，是为了拿到 AndroidX 和 MediaPipe。
#  仍然保留的 Windows 约束：
#   - 工程根目录必须纯 ASCII（aapt2 打不开中文目录）→ 项目已在 D:\filmbox-android
#   - 原生命令一律不加 2>&1（PS 5.1 会把 stderr 包成 ErrorRecord 并终止脚本）
# ============================================================
$ErrorActionPreference = 'Stop'

$root   = $PSScriptRoot
$tc     = "$root\toolchain"
$jdk    = "$tc\jdk-17"
$sdk    = "$tc\android-sdk"
$gradle = "$tc\gradle-8.9\bin\gradle.bat"
$py     = "D:\Python\python.exe"
$out    = "$root\out"

foreach ($p in @("$jdk\bin\javac.exe", $gradle, "$sdk\platforms\android-34\android.jar")) {
    if (-not (Test-Path $p)) { throw "工具链不完整，先跑 setup_toolchain.ps1（缺 $p）" }
}
if (-not (Test-Path $py)) { throw "找不到 Python: $py" }

# PATH 上的 java 是 Oracle JRE 1.8，Gradle 8.9 + AGP 起不来。
# gradle.properties 里还有一道 org.gradle.java.home，这里是第二道。
$env:JAVA_HOME        = $jdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME     = $sdk
$env:GRADLE_USER_HOME = "$root\.gradle-home"

New-Item -ItemType Directory -Force $out | Out-Null

# ---------- 1. 生成 assets ----------
Write-Output "=== [1/3] 生成 app/src/main/assets/index.html ==="
& $py "$root\build_assets.py"
if ($LASTEXITCODE -ne 0) { throw "build_assets.py 失败" }

# ---------- 2. Gradle ----------
Write-Output ""
Write-Output "=== [2/3] gradle :app:assembleRelease ==="
$sw = [System.Diagnostics.Stopwatch]::StartNew()
# 不用 -x lintVitalRelease：lint { checkReleaseBuilds false } 之下这个任务
# 根本不会被创建，-x 一个不存在的任务反而会硬失败
& $gradle -p $root :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) {
    # Gradle 守护进程偶尔占着 dex 输出文件不放（Windows 文件锁），
    # 表现为 mergeDexRelease 报「另一个程序正在使用此文件」。停掉守护进程重试一次。
    Write-Output ""
    Write-Output "  构建失败，停掉守护进程后重试一次…"
    & $gradle -p $root --stop | Out-Null
    Start-Sleep -Seconds 3
    & $gradle -p $root :app:assembleRelease --console=plain
}
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
$sw.Stop()
Write-Output ("  耗时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + "s")

# ---------- 3. 产物 ----------
Write-Output ""
Write-Output "=== [3/3] 产物 ==="
$built = "$root\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $built)) { throw "Gradle 没有产出 APK: $built" }
$apk = "$out\filmbox.apk"
Copy-Item $built $apk -Force

# aapt2 的反斜杠条目名断言：assets 已拍平，理论上不该再出现，
# 但这条路径历史上静默坏过一次（字体加载不到且不报错），所以每次构建都验
& $py "$root\fix_apk_paths.py" --assert $apk
if ($LASTEXITCODE -ne 0) { throw "APK 条目名断言失败" }

Write-Output ""
Write-Output "============================================"
# 版本号从 build.gradle 里读出来打在这里 —— 每次打包都要 +1，
# 忘了的话一眼就能看见（覆盖安装靠的就是它递增）
$g = Get-Content "$root\app\build.gradle" -Raw
$vc = ([regex]'versionCode\s+(\d+)').Match($g).Groups[1].Value
$vn = ([regex]"versionName\s+'([^']+)'").Match($g).Groups[1].Value
Write-Output ("  版本: " + $vn + "  (versionCode " + $vc + ")")
Write-Output ("  产物: " + $apk)
Write-Output ("  体积: " + [math]::Round((Get-Item $apk).Length / 1MB, 2) + " MB")
Write-Output "============================================"
