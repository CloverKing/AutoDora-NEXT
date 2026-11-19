$mingwBin = Join-Path $env:USERPROFILE "Programs\mingw64\bin"
if (Test-Path $mingwBin) {
    $env:PATH = "$mingwBin;$env:PATH"
}

$src = $PSScriptRoot
$bld = Join-Path $src "build"
cmake -S $src -B $bld -G Ninja -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
cmake --build $bld
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $bld "autodora_solver_tests.exe")
