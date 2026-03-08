<#
.SYNOPSIS
    Builds all Docker images for TFL4 benchmark (JVM, OpenJ9, Native; with and without EBICS keys).

.DESCRIPTION
    Prerequisites:
      - Docker Desktop running
      - Maven build completed: .\mvnw.cmd package -DskipTests
      - EBICS keys present in ebics/ directory (for -ek variants)

.PARAMETER Profiles
    Comma-separated list of profiles to build. Default: all.
    Valid values: jvm, openj9, native

.PARAMETER SkipEk
    Skip building EBICS (-ek) image variants.

.PARAMETER SkipMaven
    Skip the Maven package step (assumes target/jvm-optim-demo-0.0.1-SNAPSHOT.jar exists).

.EXAMPLE
    .\build-images.ps1                           # build everything
    .\build-images.ps1 -Profiles jvm,openj9      # only JVM + OpenJ9
    .\build-images.ps1 -SkipEk                   # skip EBICS variants
    .\build-images.ps1 -SkipMaven                # skip Maven build
#>
param(
    [string]$Profiles = "jvm,openj9,native",
    [switch]$SkipEk,
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"

$REPO = "tfl4-ek-bench"
$JAR  = "target/jvm-optim-demo-0.0.1-SNAPSHOT.jar"

# ── Colour helpers ──────────────────────────────────────────────────────────
function Write-Step  { param($msg) Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Write-Ok    { param($msg) Write-Host "    OK: $msg" -ForegroundColor Green }
function Write-Skip  { param($msg) Write-Host "    SKIP: $msg" -ForegroundColor Yellow }
function Write-Fail  { param($msg) Write-Host "    FAIL: $msg" -ForegroundColor Red }

# ── Maven build ─────────────────────────────────────────────────────────────
if (-not $SkipMaven) {
    Write-Step "Building Maven artifact"
    $env:JAVA_HOME = "C:\Users\mme\.jdks\temurin-25.0.1"
    & .\mvnw.cmd package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Maven build failed"; exit 1
    }
    Write-Ok "Maven package complete"
} else {
    Write-Skip "Maven build (--SkipMaven)"
}

if (-not (Test-Path $JAR)) {
    Write-Fail "JAR not found: $JAR — run Maven build first"
    exit 1
}

# ── Image definitions ───────────────────────────────────────────────────────
# Each entry: [tag, Dockerfile, description, requiresEk]
$images = @(
    @("jvm",        "Dockerfile",              "HotSpot (Temurin 25)",   $false),
    @("jvm-ek",     "Dockerfile.with-ek",      "HotSpot + EBICS",       $true),
    @("openj9",     "Dockerfile.openj9",       "OpenJ9 (Semeru 25)",    $false),
    @("openj9-ek",  "Dockerfile.openj9.with-ek","OpenJ9 + EBICS",       $true),
    @("native",     "Dockerfile.native",       "GraalVM Native Image",  $false),
    @("native-ek",  "Dockerfile.native.with-ek","Native + EBICS",       $true)
)

$requestedProfiles = $Profiles.Split(",") | ForEach-Object { $_.Trim().ToLower() }

$built   = 0
$skipped = 0
$failed  = 0

Write-Step "Building Docker images (profiles: $($requestedProfiles -join ', '))"

foreach ($img in $images) {
    $tag         = $img[0]
    $dockerfile  = $img[1]
    $description = $img[2]
    $isEk        = $img[3]

    # Determine which profile this tag belongs to
    $profile = $tag -replace "-ek$", ""

    # Skip if profile not requested
    if ($profile -notin $requestedProfiles) {
        Write-Skip "$($REPO):$tag — profile '$profile' not selected"
        $skipped++
        continue
    }

    # Skip EK variants if -SkipEk
    if ($isEk -and $SkipEk) {
        Write-Skip "$($REPO):$tag — EK variants skipped"
        $skipped++
        continue
    }

    # Check Dockerfile exists
    if (-not (Test-Path $dockerfile)) {
        Write-Fail "$($REPO):$tag — Dockerfile not found: $dockerfile"
        $failed++
        continue
    }

    Write-Step "Building $($REPO):$tag — $description"

    # Native image builds need more memory; add build arg hint
    $buildArgs = @("build", "-t", "$($REPO):$tag", "-f", $dockerfile, ".")
    if ($tag -like "native*") {
        Write-Host "    (Native image build — this may take several minutes)" -ForegroundColor DarkGray
    }

    & docker @buildArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "docker build failed for $($REPO):$tag"
        $failed++
        continue
    }
    Write-Ok "$($REPO):$tag"
    $built++
}

# ── Summary ─────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "========================================" -ForegroundColor White
Write-Host "  Built:   $built" -ForegroundColor Green
Write-Host "  Skipped: $skipped" -ForegroundColor Yellow
if ($failed -gt 0) {
    Write-Host "  Failed:  $failed" -ForegroundColor Red
} else {
    Write-Host "  Failed:  0" -ForegroundColor Green
}
Write-Host "========================================" -ForegroundColor White

if ($failed -gt 0) { exit 1 }

Write-Host "`nAll images built. Verify with:" -ForegroundColor Cyan
Write-Host "  docker images $REPO" -ForegroundColor White
