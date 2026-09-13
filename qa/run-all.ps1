# =====================================================================
# AIcap - run all automated suites (current baseline, commit 12ec35b)
# ---------------------------------------------------------------------
# ASCII-only on purpose: Windows PowerShell may parse .ps1 as GBK, so
# Chinese text inside the script body would break the parser.
#
# Usage (Windows PowerShell 5.1 or PowerShell 7):
#   & .\qa\run-all.ps1                 # everything
#   & .\qa\run-all.ps1 -SkipBackend    # frontend suites only
#   & .\qa\run-all.ps1 -SkipE2E        # backend only
#
# Prerequisites (already running):
#   - MySQL container aiguanli-mysql on host port 3307 (dev DB AIcap,
#     contract-test DB aicap_java_test)
#   - Spring Boot backend on http://127.0.0.1:8080
#   - Vite dev server on http://localhost:5173  (needed by both E2E suites)
#   - qa/node_modules installed once: cd qa; npm install
#
# NOTE: $ErrorActionPreference stays 'Continue' on purpose - native tools
# (node/npm/maven) write progress to stderr, and 'Stop' would abort the
# script on those harmless lines. Pass/fail comes from $LASTEXITCODE.
# =====================================================================
param(
    [switch]$SkipBackend,
    [switch]$SkipE2E,
    [switch]$SkipAcceptance,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-23'
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$MVN = 'E:\apache-maven-3.9.9\bin\mvn.cmd'

$results = New-Object System.Collections.Generic.List[object]

function Add-Result($name, $ok, $detail) {
    $state = 'FAIL'
    if ($ok) { $state = 'PASS' }
    $results.Add([pscustomobject]@{ Suite = $name; Result = $state; Detail = $detail })
}

function Test-Endpoint($url) {
    try {
        $r = Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500)
    } catch {
        return $false
    }
}

function Show-Tail($lines) {
    $arr = @($lines)
    $n = $arr.Count
    if ($n -gt 0) {
        $start = [Math]::Max(0, $n - 8)
        for ($i = $start; $i -lt $n; $i++) { Write-Host ('  ' + $arr[$i]) }
    }
}

Write-Host '=== AIcap automated test run ===' -ForegroundColor Cyan
Write-Host ("repo: {0}" -f $repo)

# ---- 0. preconditions -------------------------------------------------
$backendUp = Test-Endpoint 'http://127.0.0.1:8080/api/health'
$frontendUp = Test-Endpoint 'http://localhost:5173/'
$backendState = 'DOWN'
if ($backendUp) { $backendState = 'UP' }
$frontendState = 'DOWN'
if ($frontendUp) { $frontendState = 'UP' }
Write-Host ("backend  http://127.0.0.1:8080  : {0}" -f $backendState)
Write-Host ("frontend http://localhost:5173    : {0}" -f $frontendState)
if ((-not $backendUp -or -not $frontendUp) -and -not $SkipE2E) {
    Write-Warning 'backend/frontend not reachable - E2E suites need both. Start them, or pass -SkipE2E.'
}

# ---- 1. backend contract tests (JUnit) --------------------------------
if (-not $SkipBackend) {
    Write-Host "`n=== [1/3] backend contract tests: java-backend mvn -o -B test (126 cases) ===" -ForegroundColor Cyan
    # JAVA_HOME must point at JDK 23: the ambient environment may carry a JDK 8,
    # which makes surefire fork Java 8 and fail with
    # "class file version 67.0 ... only recognizes class file versions up to 52.0".
    # So this is force-overridden; pass -JavaHome to use another JDK.
    # (This file must stay ASCII-only: Windows PowerShell reads .ps1 as GBK here,
    #  and non-ASCII comment bytes can break the parser.)
    if (Test-Path (Join-Path $JavaHome 'bin\java.exe')) {
        $env:JAVA_HOME = $JavaHome
        Write-Host ("  JAVA_HOME = {0}" -f $env:JAVA_HOME)
    } else {
        Write-Warning ("JavaHome not found: {0} - falling back to current JAVA_HOME ({1})" -f $JavaHome, $env:JAVA_HOME)
    }
    Push-Location (Join-Path $repo 'java-backend')
    $log = Join-Path (Get-Location) 'target\run-all-backend.log'
    $mvnOut = & $MVN -o -B test 2>&1
    $mvnExit = $LASTEXITCODE
    $mvnOut | Out-File -FilePath $log -Encoding utf8
    $mvnOut | Select-String -Pattern 'Tests run:|BUILD' | ForEach-Object { Write-Host ('  ' + $_.Line) }
    $summary = ''
    $hit = $mvnOut | Select-String -Pattern 'Tests run:.*Failures:.*Errors:.*Skipped' | Select-Object -Last 1
    if ($hit) { $summary = $hit.Line.Trim() }
    Add-Result 'backend-contract (JUnit, 126 cases)' ($mvnExit -eq 0) $summary
    Pop-Location
}

# ---- 2. frontend current-baseline E2E (Playwright) --------------------
if (-not $SkipE2E) {
    Write-Host "`n=== [2/3] frontend E2E: qa/vue-baseline-e2e.spec.js (35 cases) ===" -ForegroundColor Cyan
    Push-Location (Join-Path $repo 'qa')
    if (-not (Test-Path 'node_modules')) {
        Write-Warning 'qa/node_modules missing - run: cd qa; npm install'
        Add-Result 'frontend-e2e (qa/, 35 cases)' $false 'qa/node_modules missing'
    } else {
        $e2eOut = & npx playwright test 2>&1
        $e2eExit = $LASTEXITCODE
        $e2eOut | Select-String -Pattern 'passed|failed|flaky' | ForEach-Object { Write-Host ('  ' + $_.Line.Trim()) }
        Show-Tail $e2eOut
        $detail = 'see qa/report/index.html'
        $hit = $e2eOut | Select-String -Pattern '\d+ passed' | Select-Object -Last 1
        if ($hit) { $detail = $hit.Line.Trim() }
        Add-Result 'frontend-e2e (qa/, 35 cases)' ($e2eExit -eq 0) $detail
    }
    Pop-Location
}

# ---- 3. acceptance suite ---------------------------------------------
if (-not $SkipAcceptance) {
    Write-Host "`n=== [3/3] acceptance suite: frontend npm run e2e:verify (7 cases) ===" -ForegroundColor Cyan
    Push-Location (Join-Path $repo 'frontend')
    $accOut = & npm run e2e:verify 2>&1
    $accExit = $LASTEXITCODE
    $accOut | Select-String -Pattern 'passed|failed|flaky' | ForEach-Object { Write-Host ('  ' + $_.Line.Trim()) }
    Show-Tail $accOut
    $detail = 'frontend/test-results'
    $hit = $accOut | Select-String -Pattern '\d+ passed' | Select-Object -Last 1
    if ($hit) { $detail = $hit.Line.Trim() }
    Add-Result 'acceptance (e2e-verify, 7 cases)' ($accExit -eq 0) $detail
    Pop-Location
}

# ---- summary ---------------------------------------------------------
Write-Host "`n=== summary ===" -ForegroundColor Cyan
foreach ($r in $results) {
    $color = 'Red'
    if ($r.Result -eq 'PASS') { $color = 'Green' }
    Write-Host ("{0,-40} {1,-5} {2}" -f $r.Suite, $r.Result, $r.Detail) -ForegroundColor $color
}
$total = $results.Count
$failed = @($results | Where-Object { $_.Result -ne 'PASS' }).Count
Write-Host ("`nsuites: {0}, failed: {1}" -f $total, $failed)
if ($failed -eq 0 -and $total -gt 0) {
    Write-Host 'ALL SUITES PASSED' -ForegroundColor Green
    exit 0
}
Write-Host ("FAILED SUITES: {0}" -f $failed) -ForegroundColor Red
exit 1
