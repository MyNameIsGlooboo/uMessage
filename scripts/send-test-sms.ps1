<#
.SYNOPSIS  Send one fake incoming SMS to a running emulator.
.EXAMPLE   .\send-test-sms.ps1 -Number 5551234 -Text "hello"
Requires adb on PATH. Set $env:ADB_SERIAL (e.g. emulator-5554) if several devices are attached.
#>
param(
    [Parameter(Mandatory = $true)][string]$Number,
    [Parameter(Mandatory = $true)][string]$Text
)
$adbArgs = @()
if ($env:ADB_SERIAL) { $adbArgs += @('-s', $env:ADB_SERIAL) }
& adb @adbArgs emu sms send $Number $Text
