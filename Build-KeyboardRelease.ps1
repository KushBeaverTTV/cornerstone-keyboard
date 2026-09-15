# Build-KeyboardRelease.ps1 - one-command signed release APK for Cornerstone Keyboard.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-KeyboardRelease.ps1 [-Lane stableRelease|unstableDebug] [-Install] [-VersionOnly]
#   -Lane      stableRelease (default): org.futo.inputmethod.latin, signed via keystore.properties.
#              unstableDebug: org.futo.inputmethod.latin.unstable - the package that is actually
#              daily-driven on the phone; signed with the repo debug keystore so it updates in place.
#   -Install   also installs to a connected device via adb (prefers a physical device).
# Signing: reads keystore.properties from this directory (gitignored; generate from
#   C:\Cornerstone\Cornerstone_Secrets\signing.env - same release identity as the other
#   Cornerstone phone apps). Never prints secret values.
# Output: releases\keyboard\<stamp>\ with the APK + SHA256SUMS.txt

param(
    [ValidateSet('stableRelease','unstableDebug')] [string]$Lane = 'stableRelease',
    [switch]$Install,
    [switch]$VersionOnly,
    [string]$OutRoot = (Join-Path $PSScriptRoot 'releases\keyboard')
)

$ErrorActionPreference = 'Stop'
$Repo  = $PSScriptRoot
$Tools = 'C:\Cornerstone\tools'

# --- Portable toolchain env (process-local) ---------------------------------
$env:JAVA_HOME        = Join-Path $Tools 'jdk-21'
$env:ANDROID_HOME     = Join-Path $Tools 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PYTHONUTF8       = '1'
$env:PATH = (@(
    (Join-Path $env:JAVA_HOME 'bin'),
    (Join-Path $env:ANDROID_HOME 'platform-tools'),
    (Join-Path $Tools 'python\cpython-3.12.5-windows-x86_64-none')
) -join ';') + ';' + $env:PATH

# --- Versioning ---------------------------------------------------------------
# unstableDebug lane: the phone's daily driver uses date-based version codes
# (e.g. 2026082204), so default to that scheme - a git-count code would be a
# downgrade and Android would refuse the update. Defaults are derived from the
# APKs already staged under $OutRoot so repeat builds never collide and the
# default name is never silently behind the newest staged release.
function Get-StagedReleases {
    # cornerstone-keyboard-<name>-<code>.apk entries, highest code first.
    if (-not (Test-Path -LiteralPath $OutRoot)) { return @() }
    @(Get-ChildItem -LiteralPath $OutRoot -Recurse -Filter 'cornerstone-keyboard-*.apk' |
        ForEach-Object {
            if ($_.Name -match '^cornerstone-keyboard-(.+)-(\d+)\.apk$') {
                [pscustomobject]@{ Name = $Matches[1]; Code = [int64]$Matches[2] }
            }
        } | Sort-Object Code -Descending)
}

function Get-NextVersionName($staged) {
    # Bump a trailing -N counter (2.0.3-fast-4 -> 2.0.3-fast-5), else bump the
    # last dotted component (2.0.3 -> 2.0.4). Fallback stays ahead of the
    # newest known shipped series.
    if ($staged.Count -eq 0) { return '2.0.4' }
    $n = $staged[0].Name
    if ($n -match '^(.*)-(\d+)$') { return $Matches[1] + '-' + ([int]$Matches[2] + 1) }
    if ($n -match '^(.*\.)(\d+)$') { return $Matches[1] + ([int]$Matches[2] + 1) }
    return "$n-1"
}

$staged = @(Get-StagedReleases)
Push-Location $Repo
try {
    $gitCodeText = ''
    $gitCodeCommandSucceeded = $false
    try {
        $gitCodeText = ((& git rev-list --first-parent --count HEAD 2>$null) | Out-String).Trim()
        $gitCodeCommandSucceeded = $LASTEXITCODE -eq 0
    } catch {
        $gitCodeText = ''
    }
    $gitCode = [int64]0
    $gitCodeAvailable = $gitCodeCommandSucceeded -and [int64]::TryParse($gitCodeText, [ref]$gitCode)
    $gitHistoryAheadOfStaged = $gitCodeAvailable -and ($staged.Count -eq 0 -or $gitCode -gt $staged[0].Code)
    if (-not $env:VERSION_CODE) {
        if ($Lane -eq 'unstableDebug') {
            $today = Get-Date -Format 'yyyyMMdd'
            $maxSeq = 0
            foreach ($r in $staged) {
                $s = $r.Code.ToString()
                if ($s.StartsWith($today) -and $s.Length -gt 8) {
                    $seq = [int]$s.Substring(8)
                    if ($seq -gt $maxSeq) { $maxSeq = $seq }
                }
            }
            $env:VERSION_CODE = $today + ($maxSeq + 1).ToString('d2')
        } else {
            if ($gitHistoryAheadOfStaged) {
                $env:VERSION_CODE = [string]$gitCode
            } elseif ($staged.Count -gt 0) {
                $env:VERSION_CODE = [string]($staged[0].Code + 1)
                Write-Output "Git version code is not above staged $($staged[0].Code); VERSION_CODE = staged +1."
            } else {
                throw 'git rev-list failed for VERSION_CODE and no staged releases exist'
            }
        }
    }
    if (-not $env:VERSION_NAME) {
        if ($Lane -eq 'unstableDebug') {
            $env:VERSION_NAME = Get-NextVersionName $staged   # gradle appends the -unstable suffix itself
        } else {
            $desc = $null
            if ($gitHistoryAheadOfStaged) {
                try {
                    $desc = (& git describe --tags 2>$null)
                } catch {
                    $desc = $null
                }
            }
            if ($desc) {
                $env:VERSION_NAME = "$desc".Trim()
            } else {
                $env:VERSION_NAME = Get-NextVersionName $staged
            }
        }
    }
} finally { Pop-Location }
Write-Output "Version: $env:VERSION_NAME ($env:VERSION_CODE)"
if ($VersionOnly) { return }

# --- Signing sanity ----------------------------------------------------------
# keystore.properties is gitignored; bootstrap it from
# C:\Cornerstone\Cornerstone_Secrets\signing.env (KEY=VALUE lines). Secret
# values are written to the file but never printed.
if ($Lane -eq 'stableRelease' -and -not (Test-Path -LiteralPath (Join-Path $Repo 'keystore.properties'))) {
    $signingEnv = 'C:\Cornerstone\Cornerstone_Secrets\signing.env'
    if (-not (Test-Path -LiteralPath $signingEnv)) {
        throw "keystore.properties missing and $signingEnv not found - provide one of them and retry."
    }
    $kv = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($signingEnv)) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $kv[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
    }
    foreach ($k in 'KBP_RELEASE_KEYSTORE_PATH','KBP_RELEASE_KEYSTORE_PASSWORD','KBP_RELEASE_KEY_ALIAS','KBP_RELEASE_KEY_PASSWORD') {
        if (-not $kv[$k]) { throw "$signingEnv is missing required key $k" }
    }
    if (-not (Test-Path -LiteralPath $kv['KBP_RELEASE_KEYSTORE_PATH'])) {
        throw "keystore file not found: $($kv['KBP_RELEASE_KEYSTORE_PATH'])"
    }
    [System.IO.File]::WriteAllLines((Join-Path $Repo 'keystore.properties'), @(
        '# Generated by Build-KeyboardRelease.ps1 from Cornerstone_Secrets\signing.env - gitignored, do not commit.',
        "storeFile=$($kv['KBP_RELEASE_KEYSTORE_PATH'] -replace '\\', '/')",
        "storePassword=$($kv['KBP_RELEASE_KEYSTORE_PASSWORD'])",
        "keyAlias=$($kv['KBP_RELEASE_KEY_ALIAS'])",
        "keyPassword=$($kv['KBP_RELEASE_KEY_PASSWORD'])"
    ))
    Write-Output 'keystore.properties generated from Cornerstone_Secrets\signing.env (values not printed).'
}

# --- Build -------------------------------------------------------------------
$gradleTask = "assemble$($Lane.Substring(0,1).ToUpper())$($Lane.Substring(1))"
Push-Location $Repo
try {
    & .\gradlew.bat --no-daemon $gradleTask
    if ($LASTEXITCODE -ne 0) { throw "gradle $gradleTask failed (exit $LASTEXITCODE)" }
} finally { Pop-Location }

$lanePath = if ($Lane -eq 'stableRelease') { 'stable\release' } else { 'unstable\debug' }
$apkDir = Join-Path $Repo "build\outputs\apk\$lanePath"
if (-not (Test-Path -LiteralPath $apkDir)) {
    # fallback: locate wherever the lane APK landed
    $laneWords = $lanePath -split '\\'
    $apkDir = Split-Path -Parent ((Get-ChildItem -LiteralPath (Join-Path $Repo 'build\outputs\apk') -Recurse -Filter *.apk |
        Where-Object { $_.FullName -match $laneWords[0] -and $_.FullName -match $laneWords[1] } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName)
}
$apk = Get-ChildItem -LiteralPath $apkDir -Filter *.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No $Lane APK produced." }

# --- Verify signature --------------------------------------------------------
$apksigner = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Recurse -Filter apksigner.bat |
             Sort-Object { $_.Directory.Name } -Descending | Select-Object -First 1
if ($apksigner) {
    & $apksigner.FullName verify --print-certs $apk.FullName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "apksigner verification failed for $($apk.Name)" }
    Write-Output 'Signature: verified'
} else {
    Write-Output 'WARNING: apksigner not found, signature not verified'
}

# --- Stage -------------------------------------------------------------------
$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $OutRoot $stamp
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$dest = Join-Path $outDir "cornerstone-keyboard-$env:VERSION_NAME-$env:VERSION_CODE.apk"
Copy-Item -LiteralPath $apk.FullName -Destination $dest
$hash = (Get-FileHash $dest -Algorithm SHA256).Hash
[System.IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS.txt'), "$hash  $(Split-Path $dest -Leaf)`n")

# Release note for every build - most staged dirs previously had none.
$pkg   = if ($Lane -eq 'stableRelease') { 'org.futo.inputmethod.latin' } else { 'org.futo.inputmethod.latin.unstable' }
$vFull = if ($Lane -eq 'unstableDebug') { "$env:VERSION_NAME-unstable" } else { $env:VERSION_NAME }
$note  = @"
Cornerstone Keyboard $env:VERSION_NAME - Release Note
=====================================================

APK
---
File: $(Split-Path $dest -Leaf)
Package: $pkg
Version: $vFull ($env:VERSION_CODE)
Lane: $Lane
Built: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') on $env:COMPUTERNAME
SHA-256: $hash
Size: $((Get-Item $dest).Length) bytes

See CHANGELOG.md at the repo root for the staged release history.
"@
[System.IO.File]::WriteAllText((Join-Path $outDir 'README.txt'), $note)
Write-Output ("Release staged: {0}  ({1:N1} MB)  sha256 {2}" -f $dest, ((Get-Item $dest).Length/1MB), $hash.Substring(0,16) + '...')

# --- Optional install --------------------------------------------------------
if ($Install) {
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $ids = @(& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
    if (-not $ids) { throw 'No adb device connected.' }
    # Prefer a physical device over an emulator when several are attached.
    $target = @($ids | Where-Object { $_ -notlike 'emulator-*' } | Select-Object -First 1)
    if (-not $target) { $target = $ids[0] }
    if ($ids.Count -gt 1) { Write-Output "Multiple devices attached ($($ids -join ', ')) - installing to $target" }
    & $adb -s $target install -r $dest
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }
    Write-Output "Installed to $target."
}
