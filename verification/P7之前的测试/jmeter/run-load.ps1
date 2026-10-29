[CmdletBinding()]
param(
    [string]$JMeterHome = 'D:\dev\apache-jmeter-5.6.3',
    [string]$HostName = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$Port = 12346,
    [string]$Database = 'verify_jmeter',
    [string]$User = 'root',
    [string]$Password = 'fulltest',
    [ValidateRange(1, 10000)][int]$Threads = 8,
    [ValidateRange(1, 3600)][int]$RampSeconds = 10,
    [ValidateRange(1, 86400)][int]$DurationSeconds = 60
)

$ErrorActionPreference = 'Stop'
$jmeterBat = Join-Path $JMeterHome 'bin\jmeter.bat'
$plan = Join-Path $PSScriptRoot 'tangdb-jdbc-load.jmx'
$driver = Join-Path $PSScriptRoot 'lib\mysql-connector-j-8.0.33.jar'
$resultRoot = Join-Path $PSScriptRoot 'results'
$runName = Get-Date -Format 'yyyyMMdd-HHmmss'
$databaseName = "${Database}_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
$resultDir = Join-Path $resultRoot $runName

if ($Database -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,30}$') {
    throw 'Database must be a simple SQL identifier prefix (letters, digits, underscore).'
}

foreach ($requiredPath in @($jmeterBat, $plan, $driver)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required file not found: $requiredPath"
    }
}

$tcp = [System.Net.Sockets.TcpClient]::new()
try {
    $tcp.Connect($HostName, $Port)
} catch {
    throw "FE MySQL endpoint is not reachable at ${HostName}:$Port. Start the isolated FE/BE test services first."
} finally {
    $tcp.Dispose()
}

New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
Set-Content -LiteralPath (Join-Path $resultDir 'database.txt') -Value $databaseName -Encoding ascii
Set-Content -LiteralPath (Join-Path $resultDir 'cleanup-db.sql') -Value "DROP DATABASE $databaseName;" -Encoding ascii
$jmeterArgs = @(
    '-n', '-t', $plan,
    "-Juser.classpath=$driver",
    "-Jhost=$HostName", "-Jport=$Port", "-Jdb=$databaseName",
    "-Juser=$User", "-Jpassword=$Password",
    "-Jthreads=$Threads", "-Jramp=$RampSeconds", "-Jduration=$DurationSeconds",
    '-l', (Join-Path $resultDir 'samples.jtl'),
    '-e', '-o', (Join-Path $resultDir 'html-report')
)

Write-Host "JMeter: $jmeterBat"
Write-Host "Plan: $plan"
Write-Host "Target: ${HostName}:$Port/$databaseName"
Write-Host "Load: threads=$Threads ramp=${RampSeconds}s duration=${DurationSeconds}s"
Write-Host "Results: $resultDir"
& $jmeterBat @jmeterArgs
if ($LASTEXITCODE -ne 0) {
    throw "JMeter exited with code $LASTEXITCODE. See $resultDir"
}

Write-Host "HTML report: $(Join-Path $resultDir 'html-report\index.html')"
Write-Host "Database: $databaseName (cleanup SQL saved to $resultDir\cleanup-db.sql)"
