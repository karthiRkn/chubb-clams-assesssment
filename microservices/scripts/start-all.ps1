<#
  Starts the three services in dependency order (User, Claim, Assessment) as background java processes.
  Each runs the runnable jar of its module with its own in-memory H2 database unless you set the *_DB_URL variables
  (USER_DB_*, CLAIM_DB_*, ASSESSMENT_DB_*: URL, USERNAME, PASSWORD; one database per service, never two services in one).

  Authentication: every service checks the same signed token, so they all need the same JWT secret, and no service has a
  default one (a secret in a file in the repository is a secret for everyone who can read it). If JWT_SECRET is set in this
  PowerShell session it is passed on to all three (at least 32 characters). If it is not, this script generates a random one
  for THIS run and passes it to the three processes only: it is never printed or written to a file, it is not left in your
  session, and tokens issued by this run stop working when you stop it. The User Service also creates four demo users
  (admin1, officer1, manager1, claimant1; password password123 unless DEMO_USERS_PASSWORD is set); set DEMO_USERS=false
  first to switch them off, and then give it a first administrator with BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD.

  Usage (from the repository root):
    .\microservices\scripts\start-all.ps1                  # build the jars first, then start
    .\microservices\scripts\start-all.ps1 -SkipBuild       # jars are already built
    .\microservices\scripts\start-all.ps1 -ClaimPort 9181  # a port is taken: the other services are pointed at the new one

  Logs and PID files go to microservices\logs. Stop with stop-all.ps1. It never stops a process it did not start.
#>
param(
    [int]$UserPort = 8185,
    [int]$ClaimPort = 8181,
    [int]$AssessmentPort = 8182,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$micro = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $micro
$logs = Join-Path $micro 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

if (-not $SkipBuild) {
    Write-Host 'Building the runnable jars (tests skipped; run the tests separately) ...'
    & (Join-Path $repo 'mvnw.cmd') -B -f (Join-Path $micro 'pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Fix the build before starting the services.' }
}

$services = @(
    @{ Name = 'user-service';       Port = $UserPort },
    @{ Name = 'claim-service';      Port = $ClaimPort },
    @{ Name = 'assessment-service'; Port = $AssessmentPort }
)

foreach ($svc in $services) {
    $busy = Get-NetTCPConnection -LocalPort $svc.Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) {
        $owner = (Get-Process -Id $busy[0].OwningProcess).ProcessName
        throw "Port $($svc.Port) for $($svc.Name) is already in use by process $($busy[0].OwningProcess) ($owner). Stop it yourself or pick another port with -UserPort / -ClaimPort / -AssessmentPort."
    }
}

# What the processes need from the environment. The previous values are put back when the script ends, so nothing is left
# behind in your PowerShell session (the started processes keep their own copy).
$saved = @{
    JWT_SECRET         = $env:JWT_SECRET
    CLAIM_SERVICE_URL  = $env:CLAIM_SERVICE_URL
    USER_SERVICE_URL   = $env:USER_SERVICE_URL
}
$secretGenerated = $false
try {
    if ([string]::IsNullOrEmpty($env:JWT_SECRET)) {
        $bytes = New-Object byte[] 48
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        $env:JWT_SECRET = -join ($bytes | ForEach-Object { $_.ToString('x2') })
        $secretGenerated = $true
    } elseif ($env:JWT_SECRET.Length -lt 32) {
        throw 'JWT_SECRET is set but shorter than 32 characters; the services would refuse to start. Use a longer value or unset it.'
    }
    # the downstream URLs the services are started with: Assessment calls Claim, Claim calls User
    $env:CLAIM_SERVICE_URL = "http://localhost:$ClaimPort"
    $env:USER_SERVICE_URL = "http://localhost:$UserPort"
    $demoUsersOn = ($env:DEMO_USERS -ne 'false')

    foreach ($svc in $services) {
        $jar = Join-Path $micro "$($svc.Name)\target\$($svc.Name)-0.0.1-SNAPSHOT-exec.jar"
        if (-not (Test-Path $jar)) { throw "Missing $jar. Run without -SkipBuild." }
        $out = Join-Path $logs "$($svc.Name).log"
        $err = Join-Path $logs "$($svc.Name).err.log"
        Write-Host "Starting $($svc.Name) on port $($svc.Port) ..."
        $p = Start-Process -FilePath 'java' -ArgumentList @('-jar', "`"$jar`"", "--server.port=$($svc.Port)") `
            -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
        Set-Content -Path (Join-Path $logs "$($svc.Name).pid") -Value $p.Id

        $deadline = (Get-Date).AddSeconds(90)
        $up = $false
        while ((Get-Date) -lt $deadline -and -not $up) {
            if ($p.HasExited) { throw "$($svc.Name) exited during startup. See $out and $err." }
            try {
                # the health endpoint needs no token and says UP only when the service and its database work
                $r = Invoke-WebRequest -Uri "http://localhost:$($svc.Port)/actuator/health" -UseBasicParsing -TimeoutSec 20
                $up = ($r.StatusCode -eq 200)
            } catch { Start-Sleep -Milliseconds 500 }
        }
        if (-not $up) { throw "$($svc.Name) did not answer on port $($svc.Port) within 90 seconds. See $out." }
    }
} finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } else { Set-Item "Env:$name" $saved[$name] }
    }
}

Write-Host ''
Write-Host 'All three services are up:'
foreach ($svc in $services) {
    Write-Host ("  {0,-20} http://localhost:{1}   health: /actuator/health   Swagger UI: /swagger-ui/index.html" -f $svc.Name, $svc.Port)
}
Write-Host ''
if ($secretGenerated) {
    Write-Host 'JWT secret:  generated for this run (not shown, not saved). Set JWT_SECRET yourself to keep tokens valid across restarts.'
}
if ($demoUsersOn) {
    Write-Host 'Demo users (DEMO ONLY, password password123 unless DEMO_USERS_PASSWORD is set): admin1 (ADMIN), officer1 (CLAIMS_OFFICER), manager1 (MANAGER), claimant1 (CLAIMANT)'
} else {
    Write-Host 'Demo users are switched off (DEMO_USERS=false): only a BOOTSTRAP_ADMIN_* administrator, if you set one, can log in.'
}
Write-Host "Log in:      POST http://localhost:$UserPort/api/v1/auth/login   then use the accessToken as 'Authorization: Bearer <token>'"
Write-Host "             or open http://localhost:$UserPort/swagger-ui/index.html, try the login examples, and use Authorize on each service"
Write-Host 'Smoke test:  .\microservices\scripts\smoke-test.ps1'
