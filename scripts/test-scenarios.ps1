<#
.SYNOPSIS  Sends four incoming SMS covering the main filter paths (see test-scenarios.sh).
.EXAMPLE   .\test-scenarios.ps1 -ContactNumber 5550100
#>
param(
    [string]$ContactNumber = '5550100',
    [string]$UnknownNumber = '5550199'
)
$send = Join-Path $PSScriptRoot 'send-test-sms.ps1'
Write-Host '1/4 contact number -> expect: inbox + notification'
& $send -Number $ContactNumber -Text 'Hi, are we still on for lunch?'; Start-Sleep 2
Write-Host '2/4 unknown number -> expect: Blocked folder only'
& $send -Number $UnknownNumber -Text 'Hello, I am a stranger writing to you.'; Start-Sleep 2
Write-Host '3/4 OTP from alphanumeric id -> expect: Blocked (OTP exemption off) or inbox (on)'
& $send -Number 'VM-HDFCBK' -Text '123456 is your OTP for login. Do not share it with anyone.'; Start-Sleep 2
Write-Host '4/4 spam keyword -> expect: Blocked (add KEYWORD rule "free prize" to test the rule path)'
& $send -Number '5550177' -Text 'Congratulations! Claim your FREE PRIZE now: bit.ly/xyz'
Write-Host 'Done.'
