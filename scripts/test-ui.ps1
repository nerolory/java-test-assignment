$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'run.ps1') -CompileOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jdkBin = 'C:/Program Files/Java/jdk-26.0.1/bin'
    $javaFxLib = Join-Path $projectRoot '.deps/javafx-sdk-26.0.2/lib'
    & "$jdkBin/javac.exe" -encoding UTF-8 --module-path $javaFxLib --add-modules javafx.controls,javafx.fxml -cp 'bin' -d '.deps/test-classes' tests/ui/DependencyInjectionTest.java tests/core/ServiceContainerTest.java tests/app/StartupPathsTest.java
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" -cp '.deps/test-classes;bin' tests.core.ServiceContainerTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" -cp '.deps/test-classes;bin' tests.app.StartupPathsTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" --module-path $javaFxLib --add-modules javafx.controls,javafx.fxml --enable-native-access=javafx.graphics -cp '.deps/test-classes;bin' tests.ui.DependencyInjectionTest
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
