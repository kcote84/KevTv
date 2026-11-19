[CmdletBinding()] param([switch]$Initialize, [switch]$SetupOnly)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Security
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData\Local\KevTV\Signing'
$ks = Join-Path $dir 'kevtv-release.p12'
$wrap = Join-Path $dir 'unlock.dpapi'
$alias = 'kevtv-release'
$java = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'JDK keytool manquant.' }
$sdk = if ($env:ANDROID_HOME) {$env:ANDROID_HOME} else { Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData\Local\Android\Sdk' }
$signer = Join-Path $sdk 'build-tools\35.0.0\apksigner.bat'
if (!(Test-Path -LiteralPath $signer)) { throw 'Android Build Tools 35.0.0 requis.' }
try {
    if (!(Test-Path -LiteralPath $ks) -or !(Test-Path -LiteralPath $wrap)) {
        if (!$Initialize -or (Test-Path -LiteralPath $ks) -or (Test-Path -LiteralPath $wrap)) {
            throw 'Cle absente ou incomplete : restaurer la cle existante. Ne jamais remplacer le certificat apres publication.'
        }
        [void][IO.Directory]::CreateDirectory($dir)
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        $raw = New-Object byte[] 48
        try { $rng.GetBytes($raw); $clear = [Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($raw)) }
        finally { [Array]::Clear($raw,0,$raw.Length); $rng.Dispose() }
        $wrapped = [Security.Cryptography.ProtectedData]::Protect($clear, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($wrap, $wrapped)
        $env:KEVTV_SIGN_RUNTIME = [Text.Encoding]::ASCII.GetString($clear)
        & $java -genkeypair -keystore $ks -storetype PKCS12 -storepass:env KEVTV_SIGN_RUNTIME -keypass:env KEVTV_SIGN_RUNTIME -alias $alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=KevTV Release'
        if($LASTEXITCODE -ne 0){ throw 'Creation du certificat echouee. Ne rien publier.' }
    } else {
        $clear = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($wrap),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        $env:KEVTV_SIGN_RUNTIME = [Text.Encoding]::ASCII.GetString($clear)
    }
    if ($SetupOnly) { Write-Output 'Certificat local de diffusion pret.'; return }
    $unsigned = Join-Path $root 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (!(Test-Path -LiteralPath $unsigned)) { throw 'Compiler assembleRelease avant de signer.' }
    $outDir = Join-Path $root 'build\livrables'
    [void][IO.Directory]::CreateDirectory($outDir)
    $out = Join-Path $outDir 'KevTV.apk'
    & $signer sign --ks $ks --ks-key-alias $alias --ks-pass env:KEVTV_SIGN_RUNTIME --key-pass env:KEVTV_SIGN_RUNTIME --v4-signing-enabled false --out $out $unsigned
    if($LASTEXITCODE -ne 0){ throw 'Signature echouee.' }
    & $signer verify --verbose --print-certs $out
    if($LASTEXITCODE -ne 0){ throw 'Signature non verifiee.' }
    (Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash
} finally {
    Remove-Item Env:\KEVTV_SIGN_RUNTIME -ErrorAction SilentlyContinue
    if ($clear -is [byte[]]) { [Array]::Clear($clear,0,$clear.Length) }
}