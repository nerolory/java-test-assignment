$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'run.ps1') -CompileOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jdkBin = 'C:/Program Files/Java/jdk-26.0.1/bin'
    $testSources = @(Get-ChildItem tests/core -Filter '*.java' | ForEach-Object FullName)
    $testSources += (Join-Path $projectRoot 'scripts/java/tools/UpdateDatabase.java')
    $testSources += (Join-Path $projectRoot 'scripts/java/tools/CreateDatabase.java')
    & "$jdkBin/javac.exe" -encoding UTF-8 -cp 'bin;.deps/jdbc/*' -d '.deps/test-classes' @testSources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" --enable-native-access=ALL-UNNAMED -cp '.deps/test-classes;bin;.deps/jdbc/*' tests.core.DatabaseTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" --enable-native-access=ALL-UNNAMED -cp '.deps/test-classes;bin;.deps/jdbc/*' tests.core.ScalingTest
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
