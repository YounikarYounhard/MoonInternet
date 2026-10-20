<#
    Registers the SYSTEM helper (MoonInternet.TunService) as a scheduled task — run by the installer,
    elevated.

    `schtasks /create /sc onstart` alone leaves Windows' defaults on the task, and three of them broke
    TUN for real people: it does not start on battery and is stopped the moment a laptop is unplugged,
    it is killed after 72 hours of running, and when it dies nothing starts it again. The app then
    found nobody on 127.0.0.1:35555 and fell back to the system proxy — "the tunnel never comes up"
    on a laptop and on a tester's machine, cured only by reinstalling, which re-created and started
    the task.

    So: start at boot AND at every logon (a no-op while it runs), no battery rules, no time limit,
    restart on failure — and let a signed-in user start it, so the app can bring it back itself
    instead of giving up.
#>
param([Parameter(Mandatory = $true)][string]$Exe, [string]$Name = 'MoonInternetTun')

$ErrorActionPreference = 'Stop'

$action = New-ScheduledTaskAction -Execute $Exe -WorkingDirectory (Split-Path $Exe)
$triggers = @((New-ScheduledTaskTrigger -AtStartup), (New-ScheduledTaskTrigger -AtLogOn))
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -StartWhenAvailable

Register-ScheduledTask -TaskName $Name -Action $action -Trigger $triggers -Principal $principal `
    -Settings $settings -Force | Out-Null

# Administrators and SYSTEM keep full control; signed-in users may read and run it — nothing else.
# Running it gives them nothing they did not have: it starts at boot anyway, and it only ever listens
# on the loopback.
$svc = New-Object -ComObject Schedule.Service
$svc.Connect()
$svc.GetFolder('\').GetTask($Name).SetSecurityDescriptor('D:(A;;FA;;;BA)(A;;FA;;;SY)(A;;GRGX;;;AU)', 0)

Start-ScheduledTask -TaskName $Name
'ok'
