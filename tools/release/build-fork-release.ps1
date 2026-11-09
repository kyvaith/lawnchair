param(
    [Parameter(Mandatory = $true)]
    [string]$SigningDirectory,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
$propertiesFile = Join-Path $signingRoot 'keystore.properties'
$passwordFile = Join-Path $signingRoot 'keystore-password.txt'
if (-not (Test-Path -LiteralPath $propertiesFile) -or -not (Test-Path -LiteralPath $passwordFile)) {
    throw 'The permanent signing key is missing. Restore its private directory; do not generate a replacement key.'
}
$previousProperties = $env:LAWNCHAIR_KEYSTORE_PROPERTIES
$previousStorePassword = $env:LAWNCHAIR_KEYSTORE_PASSWORD
$previousKeyPassword = $env:LAWNCHAIR_KEY_PASSWORD
Push-Location -LiteralPath $repositoryRoot
try {
    $env:LAWNCHAIR_KEYSTORE_PROPERTIES = $propertiesFile
    $env:LAWNCHAIR_KEYSTORE_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
    $env:LAWNCHAIR_KEY_PASSWORD = $env:LAWNCHAIR_KEYSTORE_PASSWORD
    $arguments = @('assembleLawnWithQuickstepForkRelease', 'spotlessCheck', '--no-daemon', '--no-configuration-cache', '--no-scan',
        '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process')
    if ($Offline) { $arguments += '--offline' }
    & (Join-Path $repositoryRoot 'gradlew.bat') @arguments
    if ($LASTEXITCODE -ne 0) { throw "Fork Release build failed (exit $LASTEXITCODE)." }
} finally {
    $env:LAWNCHAIR_KEYSTORE_PROPERTIES = $previousProperties
    $env:LAWNCHAIR_KEYSTORE_PASSWORD = $previousStorePassword
    $env:LAWNCHAIR_KEY_PASSWORD = $previousKeyPassword
    Pop-Location
}
