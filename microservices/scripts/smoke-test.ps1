<#
  Runs the demo flow against the three RUNNING services as real users and checks every response. Exits with 1 if anything differs.
  It logs in as an administrator, creates an officer and a manager with unique names, registers two claimants, and then does
  each step as the person whose job it is. The new users and claims stay in the databases (run it again any time: it counts
  before and after instead of assuming an empty system).

  The administrator it logs in as is, in this order: -AdminUsername / -AdminPassword; BOOTSTRAP_ADMIN_USERNAME /
  BOOTSTRAP_ADMIN_PASSWORD from this PowerShell session; otherwise the demo administrator admin1 (password password123, or
  DEMO_USERS_PASSWORD), which exists while the demo users are on. Passwords are never printed.

  Usage:
    .\microservices\scripts\smoke-test.ps1
    .\microservices\scripts\smoke-test.ps1 -ClaimUrl http://localhost:9181   # when the Claim Service uses another port
#>
param(
    [string]$UserUrl = 'http://localhost:8185',
    [string]$ClaimUrl = 'http://localhost:8181',
    [string]$AssessmentUrl = 'http://localhost:8182',
    [string]$AdminUsername = $env:BOOTSTRAP_ADMIN_USERNAME,
    [string]$AdminPassword = $env:BOOTSTRAP_ADMIN_PASSWORD
)

$script:failures = 0

if ([string]::IsNullOrEmpty($AdminUsername) -or [string]::IsNullOrEmpty($AdminPassword)) {
    # no administrator given: use the documented demo administrator (present while the demo users are on)
    $AdminUsername = 'admin1'
    $AdminPassword = if ($env:DEMO_USERS_PASSWORD) { $env:DEMO_USERS_PASSWORD } else { 'password123' }
    Write-Host "No administrator credentials given: trying the demo administrator '$AdminUsername'."
}

# HttpClient rather than Invoke-WebRequest: Windows PowerShell 5.1 loses the body of a 401 response that carries a
# WWW-Authenticate header, and the 401 body (its error code) is exactly what this script checks.
Add-Type -AssemblyName System.Net.Http
$script:http = New-Object System.Net.Http.HttpClient
$script:http.Timeout = [TimeSpan]::FromSeconds(60)

function Call($method, $url, $body, $token) {
    $request = New-Object System.Net.Http.HttpRequestMessage (New-Object System.Net.Http.HttpMethod $method), $url
    if ($token) { $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue 'Bearer', $token }
    if ($null -ne $body) {
        $request.Content = New-Object System.Net.Http.StringContent ($body | ConvertTo-Json -Depth 5), ([System.Text.Encoding]::UTF8), 'application/json'
    }
    $response = $script:http.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($text)) { $json = $text | ConvertFrom-Json }
    return @{ Status = [int]$response.StatusCode; Json = $json }
}

function Check($label, $actual, $expected) {
    if ("$actual" -eq "$expected") {
        Write-Host ("  PASS  {0} = {1}" -f $label, $actual)
    } else {
        Write-Host ("  FAIL  {0}: expected {1} but got {2}" -f $label, $expected, $actual) -ForegroundColor Red
        $script:failures++
    }
}

function Login($username, $password) {
    $r = Call 'POST' "$UserUrl/api/v1/auth/login" @{ username = $username; password = $password } $null
    if ($r.Status -ne 200) { Write-Host "  FAIL  login as $username -> HTTP $($r.Status)" -ForegroundColor Red; exit 1 }
    return $r.Json.accessToken
}

function NewClaim($token, $amount) {
    return Call 'POST' "$ClaimUrl/api/v1/claims" @{ claimantName = 'Mei Tan'; claimantEmail = 'mei.tan@example.com'; policyNumber = 'POL-2026-0042'; market = 'SG'; claimType = 'MOTOR'; incidentDescription = 'Rear bumper damaged.'; incidentDate = '2026-10-12'; claimedAmount = $amount; currency = 'INR' } $token
}

Write-Host '0. Health, login, users and roles'
foreach ($service in @(@('User', $UserUrl), @('Claim', $ClaimUrl), @('Assessment', $AssessmentUrl))) {
    $h = Call 'GET' "$($service[1])/actuator/health" $null $null
    Check "$($service[0]) health" $h.Json.status 'UP'
}
Check 'the actuator exposes nothing else' (Call 'GET' "$ClaimUrl/actuator/env" $null $null).Status 401
$noToken = Call 'GET' "$ClaimUrl/api/v1/claims/CLM-2026-000101" $null $null
Check 'no token is refused' $noToken.Status 401
Check 'no token code' $noToken.Json.code 'UNAUTHENTICATED'
$bad = Call 'POST' "$UserUrl/api/v1/auth/login" @{ username = $AdminUsername; password = 'definitely-not-the-password' } $null
Check 'wrong password is refused' $bad.Status 401
Check 'wrong password code' $bad.Json.code 'INVALID_CREDENTIALS'
$admin = Login $AdminUsername $AdminPassword
$run = Get-Date -Format 'HHmmss'
$officerName = "smoke.officer.$run"; $managerName = "smoke.manager.$run"; $claimantName = "smoke.claimant.$run"; $strangerName = "smoke.stranger.$run"
$pw = "smoke-$run-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$made = Call 'POST' "$UserUrl/api/v1/users" @{ username = $officerName; password = $pw; role = 'CLAIMS_OFFICER' } $admin
Check 'admin creates a claims officer' $made.Status 201
Check 'the new officer is active' $made.Json.active $true
Check 'admin creates a manager' (Call 'POST' "$UserUrl/api/v1/users" @{ username = $managerName; password = $pw; role = 'MANAGER' } $admin).Status 201
$reg = Call 'POST' "$UserUrl/api/v1/auth/register" @{ username = $claimantName; password = $pw } $null
Check 'claimant registers' $reg.Status 201
Check 'registration gives a CLAIMANT' $reg.Json.role 'CLAIMANT'
Check 'stranger registers' (Call 'POST' "$UserUrl/api/v1/auth/register" @{ username = $strangerName; password = $pw } $null).Status 201
$officer = Login $officerName $pw
$manager = Login $managerName $pw
$claimant = Login $claimantName $pw
$stranger = Login $strangerName $pw
Check 'a claimant cannot list users' (Call 'GET' "$UserUrl/api/v1/users" $null $claimant).Status 403
Check 'me returns the caller' (Call 'GET' "$UserUrl/api/v1/users/me" $null $officer).Json.username $officerName
$outstandingBefore = (Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $manager).Json.outstandingClaimCount

Write-Host '1. Claim Service: the claimant creates, reads, lists and corrects; the manager assigns'
$c = NewClaim $claimant 2400.00
Check 'create status' $c.Status 201
$id = $c.Json.claimId
Write-Host "  claimId = $id"
Check 'the claimant is the logged-in user' $c.Json.claimantId $claimantName
Check 'the policy number is kept' $c.Json.policyNumber 'POL-2026-0042'
Check 'owner can read the claim' (Call 'GET' "$ClaimUrl/api/v1/claims/$id" $null $claimant).Status 200
Check 'another claimant cannot (404)' (Call 'GET' "$ClaimUrl/api/v1/claims/$id" $null $stranger).Status 404
Check 'the claimant lists their own claim' (Call 'GET' "$ClaimUrl/api/v1/claims" $null $claimant).Json.content[0].claimId $id
Check 'the stranger lists none of it' (Call 'GET' "$ClaimUrl/api/v1/claims" $null $stranger).Json.totalElements 0
$fix = Call 'PATCH' "$ClaimUrl/api/v1/claims/$id" @{ claimedAmount = 2500.00 } $claimant
Check 'the claimant corrects the amount' $fix.Json.claimedAmount 2500
Check 'the correction is in the edit history' (Call 'GET' "$ClaimUrl/api/v1/claims/$id/updates" $null $claimant).Json.Count 1
Check 'another claimant cannot edit (404)' (Call 'PATCH' "$ClaimUrl/api/v1/claims/$id" @{ claimedAmount = 1 } $stranger).Status 404
Check 'a claimant cannot search the staff queue' (Call 'GET' "$ClaimUrl/api/v1/officer/claims" $null $claimant).Status 403
Check 'the officer finds it in the unassigned queue' (Call 'GET' "$ClaimUrl/api/v1/officer/claims?assigned=false&status=SUBMITTED" $null $officer).Json.content[0].claimId $id
Check 'a claimant cannot assign' (Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id/assign" @{ officerId = $officerName } $claimant).Status 403
$notOfficer = Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id/assign" @{ officerId = $claimantName } $manager
Check 'a manager cannot assign to a non-officer' $notOfficer.Status 400
Check 'that is INVALID_OFFICER' $notOfficer.Json.code 'INVALID_OFFICER'
Check 'an officer cannot give a claim to someone else' (Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id/assign" @{ officerId = $managerName } $officer).Status 403
$as = Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id/assign" @{ officerId = $officerName } $manager
Check 'the manager assigns it to the officer' $as.Status 200
Check 'it is assigned' $as.Json.assignedOfficerId $officerName
Check 'the assignment records the manager' (Call 'GET' "$ClaimUrl/api/v1/claims/$id/assignments" $null $manager).Json.actor $managerName
Check 'a person cannot use an internal route' (Call 'GET' "$ClaimUrl/internal/v1/claims/$id/status" $null $admin).Status 403

Write-Host '2. Assessment Service: review with a note (it calls the Claim Service with a service token)'
Check 'a manager cannot review' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/review" @{ officerId = $officerName } $manager).Status 403
Check 'an officer cannot review in another officers name' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/review" @{ officerId = 'somebody.else' } $officer).Status 403
$r = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/review" @{ assessmentNotes = 'Photos match; need the repair estimate.' } $officer
Check 'review status' $r.Status 200
Check 'claim status' $r.Json.status 'UNDER_REVIEW'
Check 'the claimant can no longer edit (409)' (Call 'PATCH' "$ClaimUrl/api/v1/claims/$id" @{ claimedAmount = 1 } $claimant).Status 409

Write-Host '3. Assessment Service: information request and answer'
$ir = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/information-requests" @{ question = 'Please provide the repair estimate.' } $officer
Check 'information request status' $ir.Status 201
Check 'claim needs more information' (Call 'GET' "$ClaimUrl/api/v1/claims/$id" $null $claimant).Json.status 'MORE_INFO_REQUIRED'
Check 'list count' (Call 'GET' "$AssessmentUrl/api/v1/claims/$id/information-requests" $null $claimant).Json.Count 1
Check 'another claimant cannot list (404)' (Call 'GET' "$AssessmentUrl/api/v1/claims/$id/information-requests" $null $stranger).Status 404
$body = @{ informationRequestId = $ir.Json.informationRequestId; answer = 'Estimate is 2,200 INR.' }
Check 'another claimant cannot answer (404)' (Call 'POST' "$AssessmentUrl/api/v1/claims/$id/information" $body $stranger).Status 404
Check 'an officer cannot answer for the claimant' (Call 'POST' "$AssessmentUrl/api/v1/claims/$id/information" $body $officer).Status 403
$a = Call 'POST' "$AssessmentUrl/api/v1/claims/$id/information" $body $claimant
Check 'answer status' $a.Status 200
Check 'claim resumed automatically' (Call 'GET' "$ClaimUrl/api/v1/claims/$id" $null $claimant).Json.status 'UNDER_REVIEW'

Write-Host '4. Assessment history is officer-only'
Check 'the note is saved' (Call 'GET' "$AssessmentUrl/api/v1/officer/claims/$id/assessments" $null $officer).Json.notes 'Photos match; need the repair estimate.'
Check 'a claimant cannot read it' (Call 'GET' "$AssessmentUrl/api/v1/officer/claims/$id/assessments" $null $claimant).Status 403
Check 'a manager cannot read it' (Call 'GET' "$AssessmentUrl/api/v1/officer/claims/$id/assessments" $null $manager).Status 403
Check 'the claimant view has no notes' ($null -eq (Call 'GET' "$ClaimUrl/api/v1/claims/$id" $null $claimant).Json.assessmentNotes) $true

Write-Host '5. Management reports (in the Claim Service): managers only'
Check 'a claimant cannot see the summary' (Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $claimant).Status 403
Check 'an officer cannot see the summary' (Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $officer).Status 403
$s = Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $manager
Check 'summary status' $s.Status 200
Check 'one more outstanding claim' ($s.Json.outstandingClaimCount - $outstandingBefore) 1

Write-Host '6. Assessment Service: approval, a duplicate decision, then settlement'
$d = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/decision" @{ decision = 'APPROVED'; decisionReason = 'Estimate approved.'; approvedAmount = 2200.00; currency = 'INR' } $officer
Check 'decision status' $d.Status 200
Check 'decision result' $d.Json.status 'APPROVED'
Check 'approved amount is 2200' ([decimal]$d.Json.approvedAmount -eq 2200) $true
$dup = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/decision" @{ decision = 'REJECTED'; decisionReason = 'x'; currency = 'INR' } $officer
Check 'duplicate decision status' $dup.Status 409
Check 'duplicate decision code' $dup.Json.code 'INVALID_CLAIM_TRANSITION'
Check 'a claimant cannot settle' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/settlement" @{} $claimant).Status 403
Check 'approved is still outstanding' ((Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $manager).Json.outstandingClaimCount - $outstandingBefore) 1
$settled = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/settlement" @{ settlementNote = 'Paid by bank transfer.' } $officer
Check 'settlement status' $settled.Status 200
Check 'settlement result' $settled.Json.status 'SETTLED'
Check 'a second settlement is refused' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id/settlement" @{} $officer).Status 409

Write-Host '7. A rejected claim can never be settled'
$id2 = (NewClaim $claimant 100.00).Json.claimId
Check 'the officer takes the claim for themselves' (Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id2/assign" @{ officerId = $officerName } $officer).Status 200
Check 'review' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id2/review" @{} $officer).Status 200
Check 'rejection' (Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id2/decision" @{ decision = 'REJECTED'; decisionReason = 'Not covered.'; currency = 'INR' } $officer).Json.status 'REJECTED'
$noSettle = Call 'POST' "$AssessmentUrl/api/v1/officer/claims/$id2/settlement" @{} $officer
Check 'settling a rejected claim' $noSettle.Status 409
Check 'with a clear message' $noSettle.Json.message 'A rejected claim cannot be settled'

Write-Host '8. Reports after the decisions, history, unknown claim'
Check 'nothing more is outstanding' ((Call 'GET' "$ClaimUrl/api/v1/management/summary" $null $manager).Json.outstandingClaimCount - $outstandingBefore) 0
Check 'officer metrics status' (Call 'GET' "$ClaimUrl/api/v1/management/officers" $null $manager).Status 200
Check 'status history rows' (Call 'GET' "$ClaimUrl/api/v1/claims/$id/history" $null $claimant).Json.Count 6
Check 'assessment history entries' (Call 'GET' "$AssessmentUrl/api/v1/officer/claims/$id/assessments" $null $officer).Json.Count 3
$missing = Call 'GET' "$ClaimUrl/api/v1/claims/CLM-2026-999999" $null $manager
Check 'unknown claim status' $missing.Status 404
Check 'unknown claim code' $missing.Json.code 'CLAIM_NOT_FOUND'

Write-Host '9. Switching a person off'
Check 'admin deactivates the officer' (Call 'PUT' "$UserUrl/api/v1/users/$officerName/active" @{ active = $false } $admin).Json.active $false
Check 'a deactivated officer cannot log in' (Call 'POST' "$UserUrl/api/v1/auth/login" @{ username = $officerName; password = $pw } $null).Status 401
$id3 = (NewClaim $claimant 50.00).Json.claimId
Check 'and cannot be assigned a claim' (Call 'POST' "$ClaimUrl/api/v1/officer/claims/$id3/assign" @{ officerId = $officerName } $manager).Json.code 'INVALID_OFFICER'

Write-Host ''
if ($script:failures -eq 0) { Write-Host 'Smoke test passed.' -ForegroundColor Green; exit 0 }
Write-Host "Smoke test FAILED: $($script:failures) check(s) differed." -ForegroundColor Red
exit 1
