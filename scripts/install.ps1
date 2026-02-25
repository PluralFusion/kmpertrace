$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Repo = "pluralfusion/kmpertrace"
$Version = "latest"
$InstallRoot = "$(Join-Path $env:USERPROFILE '.kmpertrace\\opt')"
$BinDir = "$(Join-Path $env:USERPROFILE '.kmpertrace\\bin')"
$BaseUrlOverride = $env:KMPERTRACE_RELEASE_BASE_URL

function Show-Usage {
  Write-Host @"
Install kmpertrace-cli from GitHub Releases.

Usage:
  install.ps1 [--version <x.y.z|cli-vx.y.z|latest>] [--install-root <dir>] [--bin-dir <dir>] [--base-url <url>]

Defaults:
  --version latest
  --install-root $InstallRoot
  --bin-dir $BinDir
"@
}

for ($i = 0; $i -lt $args.Count; $i++) {
  switch ($args[$i]) {
    "--version" {
      if ($i + 1 -ge $args.Count) { throw "Missing value for --version" }
      $Version = $args[$i + 1]
      $i++
    }
    "--install-root" {
      if ($i + 1 -ge $args.Count) { throw "Missing value for --install-root" }
      $InstallRoot = $args[$i + 1]
      $i++
    }
    "--bin-dir" {
      if ($i + 1 -ge $args.Count) { throw "Missing value for --bin-dir" }
      $BinDir = $args[$i + 1]
      $i++
    }
    "--base-url" {
      if ($i + 1 -ge $args.Count) { throw "Missing value for --base-url" }
      $BaseUrlOverride = $args[$i + 1]
      $i++
    }
    "-h" { Show-Usage; return }
    "--help" { Show-Usage; return }
    default { throw "Unknown argument: $($args[$i])" }
  }
}

function Write-Info {
  param([string]$Message)
  Write-Host "[kmpertrace-cli installer] $Message"
}

function Resolve-CliTag {
  param([string]$InputVersion)

  if ($InputVersion -eq "latest") {
    $headers = @{
      "Accept" = "application/vnd.github+json"
      "User-Agent" = "kmpertrace-cli-installer"
    }
    $releases = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases?per_page=100"
    $match = $releases | Where-Object { $_.tag_name -like "cli-v*" } | Select-Object -First 1
    if (-not $match) {
      throw "Could not find a cli-v* release tag"
    }
    return $match.tag_name
  }

  if ($InputVersion.StartsWith("cli-v")) {
    return $InputVersion
  }

  return "cli-v$InputVersion"
}

if (-not [string]::IsNullOrWhiteSpace($BaseUrlOverride) -and $Version -eq "latest") {
  throw "--base-url requires an explicit --version"
}

$tag = Resolve-CliTag -InputVersion $Version
if ($tag -notmatch '^cli-v([0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?)$') {
  throw "Invalid CLI tag format: $tag"
}

$cliVersion = $Matches[1]
$archiveName = "kmpertrace-cli-$cliVersion.zip"

$baseUrl = if ([string]::IsNullOrWhiteSpace($BaseUrlOverride)) {
  "https://github.com/$Repo/releases/download/$tag"
} else {
  $BaseUrlOverride
}

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("kmpertrace-cli-install-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
  $archivePath = Join-Path $tempDir $archiveName
  $sumsPath = Join-Path $tempDir "SHA256SUMS"

  Write-Info "Installing tag $tag"
  Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$archiveName" -OutFile $archivePath
  Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/SHA256SUMS" -OutFile $sumsPath

  $line = Get-Content $sumsPath |
    Where-Object { $_ -match "^[a-fA-F0-9]{64}\s+\*?$([regex]::Escape($archiveName))$" } |
    Select-Object -First 1

  if (-not $line) {
    throw "No checksum entry found for $archiveName"
  }

  $expected = ([regex]::Match($line, "([a-fA-F0-9]{64})")).Groups[1].Value.ToLowerInvariant()
  $actual = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($expected -ne $actual) {
    throw "Checksum mismatch for $archiveName"
  }

  New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
  New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

  $targetDir = Join-Path $InstallRoot "kmpertrace-cli-$cliVersion"
  if (Test-Path $targetDir) {
    Remove-Item -Path $targetDir -Recurse -Force
  }

  Expand-Archive -Path $archivePath -DestinationPath $InstallRoot -Force

  $launcherBat = Join-Path $targetDir "bin\kmpertrace-cli.bat"
  $shimCmd = Join-Path $BinDir "kmpertrace-cli.cmd"
  $shimPs1 = Join-Path $BinDir "kmpertrace-cli.ps1"

  Set-Content -Path $shimCmd -Encoding Ascii -Value "@echo off`r`n`"$launcherBat`" %*`r`n"
  Set-Content -Path $shimPs1 -Encoding Ascii -Value "& `"$launcherBat`" @args`r`n"

  $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
  $pathUpdated = $false
  if ([string]::IsNullOrWhiteSpace($userPath)) {
    [Environment]::SetEnvironmentVariable("Path", $BinDir, "User")
    $pathUpdated = $true
  } elseif ($userPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$BinDir", "User")
    $pathUpdated = $true
  }

  Write-Info "Installed to: $targetDir"
  Write-Info "Launcher shim: $shimCmd"

  if ($pathUpdated) {
    Write-Info "Updated user PATH. Open a new terminal and run: kmpertrace-cli --help"
  } else {
    Write-Info "Run: kmpertrace-cli --help"
  }
}
finally {
  if (Test-Path $tempDir) {
    Remove-Item -Path $tempDir -Recurse -Force
  }
}
