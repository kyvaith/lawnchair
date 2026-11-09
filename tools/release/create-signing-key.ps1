param(
    [Parameter(Mandatory = $true)]
    [string]$SigningDirectory
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
if ($signingRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the private signing directory outside the source repository.'
}
if (Test-Path -LiteralPath $signingRoot) {
    throw 'The signing directory already exists. Reuse the permanent key; do not regenerate it.'
}
if (-not $IsWindows) {
    throw 'This local key-creation helper requires Windows file permissions.'
}
$publicDirectory = Join-Path $repositoryRoot 'fork'
if ((Test-Path -LiteralPath (Join-Path $publicDirectory 'release-certificate.pem')) -or
    (Test-Path -LiteralPath (Join-Path $publicDirectory 'release-certificate.sha256'))) {
    throw 'A fork certificate is already registered. Restore its private key instead of replacing it.'
}
$null = Get-Command keytool -ErrorAction Stop
$null = New-Item -ItemType Directory -Path $signingRoot
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($identity.User)
$acl.SetAccessRuleProtection($true, $false)
foreach ($sid in @($identity.User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $sid, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow'))
}
Set-Acl -LiteralPath $signingRoot -AclObject $acl
$passwordFile = Join-Path $signingRoot 'keystore-password.txt'
$storeFile = Join-Path $signingRoot 'lawnchair-drawerless-release.p12'
$alias = 'lawnchair-drawerless'
$password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[IO.File]::WriteAllText($passwordFile, $password, [Text.UTF8Encoding]::new($false))
$previousPassword = $env:LAWNCHAIR_NEW_KEY_PASSWORD
try {
    $env:LAWNCHAIR_NEW_KEY_PASSWORD = $password
    & keytool -genkeypair -noprompt -storetype PKCS12 -keystore $storeFile -alias $alias `
        -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 `
        -dname 'CN=Lawnchair Drawerless, O=Kyvaith' `
        -storepass:env LAWNCHAIR_NEW_KEY_PASSWORD -keypass:env LAWNCHAIR_NEW_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Preserve the signing directory for diagnosis.' }
    $null = New-Item -ItemType Directory -Path $publicDirectory -Force
    $certificateFile = Join-Path $publicDirectory 'release-certificate.pem'
    & keytool -exportcert -rfc -keystore $storeFile -alias $alias `
        -storepass:env LAWNCHAIR_NEW_KEY_PASSWORD -file $certificateFile
    if ($LASTEXITCODE -ne 0) { throw 'Public certificate export failed. Preserve the private key.' }
    $certificateText = [IO.File]::ReadAllText($certificateFile)
    $certificateDer = [Convert]::FromBase64String(($certificateText -replace '-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s', ''))
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificateDer)).ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $publicDirectory 'release-certificate.sha256'), $fingerprint + "`n", [Text.UTF8Encoding]::new($false))
    $properties = "storeFile=$($storeFile.Replace('\', '/'))`nkeyAlias=$alias`n"
    [IO.File]::WriteAllText((Join-Path $signingRoot 'keystore.properties'), $properties, [Text.UTF8Encoding]::new($false))
    [ordered]@{
        applicationId = 'app.kyvaith.lawnchair'
        alias = $alias
        certificateSha256 = $fingerprint
        createdAt = [DateTime]::UtcNow.ToString('o')
        storeFile = [IO.Path]::GetFileName($storeFile)
        recovery = 'Back up this entire private directory securely, including keystore-password.txt. Never publish it or add it to Git.'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $signingRoot 'signing-info.json') -Encoding utf8
    Write-Output "Created the permanent fork key. Public certificate SHA-256: $fingerprint"
} finally {
    $env:LAWNCHAIR_NEW_KEY_PASSWORD = $previousPassword
    $password = $null
}
