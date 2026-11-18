$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# Java 17 for AGP 8.x
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    if (Test-Path "C:\Java\jdk-17") {
        $env:JAVA_HOME = "C:\Java\jdk-17"
    } else {
        Write-Host "JAVA_HOME is not set and C:\Java\jdk-17 was not found. Install JDK 17 first." -ForegroundColor Red
        exit 1
    }
}

$gradlew = Join-Path $root "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Host "gradlew.bat not found in $root" -ForegroundColor Red
    exit 1
}

# 构建结束后清理 Java 进程：先让 Gradle 守护进程正常退出，再清理残留的 JVM
function Clear-JavaProcesses {
    & $gradlew --stop 2>$null | Out-Null
    Start-Sleep -Milliseconds 800
    $java = @(Get-Process -Name java, javaw -ErrorAction SilentlyContinue)
    if ($java.Count -gt 0) {
        $java | Stop-Process -Force -ErrorAction SilentlyContinue
        Write-Host ("==> Cleaned up {0} Java process(es)." -f $java.Count) -ForegroundColor DarkGray
    }
}

$exitCode = 0
try {
    Write-Host "==> Building release APK..." -ForegroundColor Cyan
    & $gradlew :app:assembleRelease :app:lintRelease --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed (exit code $LASTEXITCODE)." -ForegroundColor Red
        $exitCode = $LASTEXITCODE
    } else {
        # Read versionName from app/build.gradle.kts
        $version = "unknown"
        $gradleFile = Join-Path $root "app\build.gradle.kts"
        if (Test-Path $gradleFile) {
            $content = Get-Content $gradleFile -Raw
            if ($content -match 'versionName\s*=\s*"([^"]+)"') {
                $version = $Matches[1]
            }
        }

        $apkDir = Join-Path $root "app\build\outputs\apk\release"
        $apks = @(Get-ChildItem -Path $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue)
        if ($apks.Count -eq 0) {
            Write-Host "Release APK not found in: $apkDir" -ForegroundColor Red
            $exitCode = 1
        } else {
            Write-Host ""
            Write-Host "Build succeeded." -ForegroundColor Green
            Write-Host ("Version : {0}" -f $version)
            foreach ($apk in $apks) {
                # app-arm64-v8a-release.apk -> arm64-v8a
                $abi = $apk.BaseName -replace '^app-', '' -replace '-release$', ''
                $fileName = if ($abi) { "MusePlayer_v{0}_{1}.apk" -f $version, $abi } else { "MusePlayer_v{0}.apk" -f $version }
                $target = Join-Path $root $fileName
                Copy-Item $apk.FullName $target -Force
                $sizeMb = [math]::Round((Get-Item $target).Length / 1MB, 1)
                Write-Host ("APK     : {0} ({1} MB)" -f $target, $sizeMb)
            }
        }
    }
} finally {
    Clear-JavaProcesses
}

exit $exitCode
