$GradleArgs = if ($args.Count -gt 0) { $args } else { @(":core-model:test", ":app:assembleDebug") }

$toolsRoot = Join-Path $env:USERPROFILE "Programs"

# Prefer JDK 21 (AGP 8.7+ requires JDK 17/21 even if system JAVA_HOME is JDK 8)
$jdk21 = Get-ChildItem -Path $toolsRoot -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk21) {
    $env:JAVA_HOME = $jdk21.FullName
}

if (-not $env:ANDROID_HOME) {
    $candidateSdk = Join-Path $toolsRoot "android-sdk"
    if (Test-Path $candidateSdk) {
        $env:ANDROID_HOME = $candidateSdk
        $env:ANDROID_SDK_ROOT = $candidateSdk
    }
}

$gradleDir = Get-ChildItem -Path $toolsRoot -Directory -Filter "gradle-*" -ErrorAction SilentlyContinue | Select-Object -First 1
$gradleBin = if ($gradleDir -and (Test-Path (Join-Path $gradleDir.FullName "bin\gradle.bat"))) {
    Join-Path $gradleDir.FullName "bin\gradle.bat"
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    "gradle"
} else {
    throw "Gradle not found. Please install Gradle 8.11+ or add it to PATH."
}

$extraPaths = @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin" }),
    $(if ($gradleDir) { Join-Path $gradleDir.FullName "bin" }),
    (Join-Path $toolsRoot "mingw64\bin"),
    (Join-Path $toolsRoot "android-cli")
) | Where-Object { $_ -and (Test-Path $_) }

if ($extraPaths.Count -gt 0) {
    $env:PATH = ($extraPaths -join ";") + ";" + $env:PATH
}

Push-Location $PSScriptRoot
try {
    & $gradleBin @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
