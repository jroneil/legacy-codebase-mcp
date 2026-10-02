param(
    [Parameter(Position = 0)]
    [string]$RepositoryPath,
    [switch]$Check
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Error "run-local.ps1: $Message"
    exit 1
}

if ([string]::IsNullOrWhiteSpace($RepositoryPath)) {
    $onWindows = $IsWindows -or $env:OS -eq "Windows_NT"
    if (-not $onWindows) {
        Fail "the native PowerShell picker is available on Windows only; pass an explicit repository path"
    }
    Add-Type -AssemblyName System.Windows.Forms
    $picker = New-Object System.Windows.Forms.FolderBrowserDialog
    $picker.Description = "Select repository folder"
    if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) {
        Fail "repository selection was cancelled"
    }
    $RepositoryPath = $picker.SelectedPath
}

if (-not (Test-Path -LiteralPath $RepositoryPath -PathType Container)) {
    Fail "repository path does not exist or is not a directory: $RepositoryPath"
}
$repository = (Resolve-Path -LiteralPath $RepositoryPath).ProviderPath
$root = [System.IO.Path]::GetPathRoot($repository)
if ($repository.TrimEnd('\', '/') -eq $root.TrimEnd('\', '/')) {
    Fail "refusing to mount a drive or filesystem root: $repository"
}
if ($env:USERPROFILE) {
    $profile = (Resolve-Path -LiteralPath $env:USERPROFILE).ProviderPath
    if ($repository.TrimEnd('\', '/') -eq $profile.TrimEnd('\', '/')) {
        Fail "refusing to mount the entire user home: $repository"
    }
}
$name = Split-Path -Leaf $repository
if ([string]::IsNullOrWhiteSpace($name)) { Fail "repository name is empty" }
if ([regex]::IsMatch($repository, '[\x00-\x1F\x7F]') -or
    [regex]::IsMatch($name, '[\x00-\x1F\x7F]')) {
    Fail "repository path contains unsupported control characters"
}

Write-Host "Repository: $name"
Write-Host "Host path: $repository"
Write-Host "Container mount: /workspace/target (read-only)"
if ($Check) { exit 0 }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail "docker is not installed or not on PATH" }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot ".env")) -and
    [string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    Fail "create .env from .env.example and set POSTGRES_PASSWORD"
}
$env:LEGACY_REPOSITORY_ROOT = $repository
$env:LEGACY_REPOSITORY_NAME = $name
$env:LEGACY_REPOSITORY_CONFIGURED = "true"
Push-Location $PSScriptRoot
try {
    $existing = $true
    foreach ($service in @("postgres", "backend", "frontend")) {
        $container = & docker compose ps --all --quiet $service
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($container)) {
            $existing = $false
        }
    }
    if ($existing) {
        & docker compose up -d --wait postgres
        if ($LASTEXITCODE -ne 0) { Fail "PostgreSQL startup failed" }
        & docker compose up -d --wait --no-deps --force-recreate backend
        if ($LASTEXITCODE -ne 0) { Fail "backend recreation failed" }
        & docker compose up -d --wait --no-deps --no-recreate frontend
        if ($LASTEXITCODE -ne 0) { Fail "frontend startup failed" }
    } else {
        & docker compose up --build -d --wait
        if ($LASTEXITCODE -ne 0) { Fail "Docker Compose startup failed" }
    }
} finally {
    Pop-Location
}
$port = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { "3000" }
Write-Host "`nReady: http://127.0.0.1:$port/scan"
