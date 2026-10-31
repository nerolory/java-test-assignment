param([string]$FxmlPath = (Join-Path $PSScriptRoot 'resources/fxml/pages/login.fxml'))

$ErrorActionPreference = 'Stop'
$candidates = @(
    (Join-Path $env:LOCALAPPDATA 'SceneBuilder/SceneBuilder.exe'),
    (Join-Path $env:ProgramFiles 'SceneBuilder/SceneBuilder.exe')
)
$sceneBuilder = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $sceneBuilder) {
    throw 'Scene Builder is not installed in LocalAppData/SceneBuilder or Program Files/SceneBuilder.'
}
$fxml = (Resolve-Path -LiteralPath $FxmlPath).Path
& $sceneBuilder $fxml
