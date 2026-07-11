<#
.SYNOPSIS
Automatisiert die Meilenstein-2-Szenarien 1 bis 3 auf einem per adb verbundenen Gerät.

.DESCRIPTION
Das Telefon darf während des gesamten Laufs nicht benutzt werden. Jede fremde
Interaktion kann Vordergrundevents und damit die Messung verfälschen. Das Skript
automatisiert bewusst keine Screen-off-Szenarien, weil der Keyguard nicht per adb
entsperrt wird.
#>

[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$TargetPackage = "com.sec.android.app.popupcalculator",
    [int]$DurationToleranceSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:AppPackage = "de.kilian.applimit"
$script:AccessibilityService =
    "de.kilian.applimit/de.kilian.applimit.service.AppLimitAccessibilityService"
$script:DatabaseFiles = @("app_limit.db", "app_limit.db-wal")
$script:DebounceMillis = 60000L
$script:PostFlushSeconds = 2
$script:FreshnessTimeoutSeconds = 180

function Resolve-AdbPath {
    $projectAdb = Join-Path $PSScriptRoot ".toolchain\android-sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $projectAdb) {
        return (Resolve-Path -LiteralPath $projectAdb).Path
    }

    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "adb wurde weder im Projekt noch im PATH gefunden."
    }
    return $command.Source
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArguments
    )

    $output = & $script:AdbPath -s $script:DeviceSerial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb fehlgeschlagen: adb $($AdbArguments -join ' ')`n$($output -join "`n")"
    }
    return (($output | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Resolve-DeviceSerial {
    param([string]$RequestedSerial)

    $output = & $script:AdbPath devices -l 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices ist fehlgeschlagen: $($output -join "`n")"
    }

    $devices = @(
        $output |
            ForEach-Object { $_.ToString() } |
            Where-Object { $_ -match "^(\S+)\s+device(?:\s|$)" } |
            ForEach-Object { $Matches[1] }
    )

    if ($RequestedSerial) {
        if ($devices -notcontains $RequestedSerial) {
            throw "Gerät '$RequestedSerial' ist nicht im Status 'device'. Verbunden: $($devices -join ', ')"
        }
        return $RequestedSerial
    }

    if ($devices.Count -ne 1) {
        throw "Ohne -DeviceSerial muss genau ein adb-Gerät verbunden sein. Gefunden: $($devices -join ', ')"
    }
    return $devices[0]
}

function Enter-VerificationMutex {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $identity = "$Serial-$PackageName" -replace "[^A-Za-z0-9_.-]", "_"
    $mutex = [Threading.Mutex]::new($false, "Local\AppLimitVerify-$identity")
    $acquired = $false
    try {
        $acquired = $mutex.WaitOne(0)
    } catch [Threading.AbandonedMutexException] {
        $acquired = $true
    }

    if (-not $acquired) {
        $mutex.Dispose()
        throw (
            "Für Gerät '$Serial' und Ziel-App '$PackageName' läuft bereits eine " +
            "AppLimit-Verifikation. Parallele Läufe würden die Messung verfälschen."
        )
    }
    return $mutex
}

function Exit-VerificationMutex {
    param([Threading.Mutex]$Mutex)

    if ($null -eq $Mutex) {
        return
    }
    try {
        $Mutex.ReleaseMutex()
    } finally {
        $Mutex.Dispose()
    }
}

function Export-RunAsFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemoteName,
        [Parameter(Mandatory = $true)]
        [string]$LocalPath
    )

    $stderrPath = "$LocalPath.stderr.txt"
    $arguments = @(
        "-s",
        $script:DeviceSerial,
        "exec-out",
        "run-as",
        $script:AppPackage,
        "cat",
        "databases/$RemoteName"
    )
    $startProcessArguments = @{
        FilePath = $script:AdbPath
        ArgumentList = $arguments
        RedirectStandardOutput = $LocalPath
        RedirectStandardError = $stderrPath
        NoNewWindow = $true
        Wait = $true
        PassThru = $true
    }
    $process = Start-Process @startProcessArguments

    if ($process.ExitCode -ne 0) {
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            Get-Content -Raw -LiteralPath $stderrPath
        } else {
            ""
        }
        throw "run-as-Export von $RemoteName fehlgeschlagen: $stderr"
    }
    if ((Get-Item -LiteralPath $LocalPath).Length -eq 0) {
        throw "run-as-Export von $RemoteName ergab eine leere Datei."
    }
}

function Get-RoomStateOnce {
    $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $tempDirectory = Join-Path $tempBase ("applimit-verify-" + [guid]::NewGuid().ToString("N"))
    [IO.Directory]::CreateDirectory($tempDirectory) | Out-Null

    try {
        foreach ($name in $script:DatabaseFiles) {
            Export-RunAsFile -RemoteName $name -LocalPath (Join-Path $tempDirectory $name)
        }

        $databasePath = Join-Path $tempDirectory "app_limit.db"
        $query = @'
import json
import sqlite3
import sys
from pathlib import Path

database_path = Path(sys.argv[1]).resolve()
date = sys.argv[2]
package_name = sys.argv[3]
connection = sqlite3.connect(database_path.as_uri() + "?mode=ro", uri=True, timeout=5)
connection.row_factory = sqlite3.Row
integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
if integrity != "ok":
    raise RuntimeError(f"SQLite integrity_check: {integrity}")
counter = connection.execute(
    "SELECT openings, durationMillis FROM daily_counters WHERE date = ? AND packageName = ?",
    (date, package_name),
).fetchone()
session_count = connection.execute(
    "SELECT COUNT(*) FROM usage_sessions WHERE packageName = ?",
    (package_name,),
).fetchone()[0]
live_session = connection.execute(
    """
    SELECT inactiveSinceEpochMillis, endedAtEpochMillis
    FROM usage_sessions
    WHERE packageName = ? AND isFinalized = 0
    ORDER BY startedAtEpochMillis DESC
    LIMIT 1
    """,
    (package_name,),
).fetchone()
print(json.dumps({
    "openings": int(counter["openings"]) if counter else 0,
    "durationMillis": int(counter["durationMillis"]) if counter else 0,
    "sessionCount": int(session_count),
    "liveInactiveSinceEpochMillis": (
        int(live_session["inactiveSinceEpochMillis"])
        if live_session and live_session["inactiveSinceEpochMillis"] is not None
        else None
    ),
    "liveEndedAtEpochMillis": (
        int(live_session["endedAtEpochMillis"]) if live_session else None
    ),
    "hasLiveSession": live_session is not None,
}))
connection.close()
'@

        $json = $query | & $script:PythonPath - $databasePath $script:DeviceDate $TargetPackage
        if ($LASTEXITCODE -ne 0) {
            throw "Lokale SQLite-Abfrage ist fehlgeschlagen."
        }
        return ($json | ConvertFrom-Json)
    } finally {
        $resolvedTemp = [IO.Path]::GetFullPath($tempDirectory)
        $tempPrefix = $tempBase.TrimEnd("\", "/") + [IO.Path]::DirectorySeparatorChar
        if ($resolvedTemp.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-RoomState {
    $lastError = $null
    $previousState = $null
    $previousFingerprint = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $state = Get-RoomStateOnce
            $fingerprint = @(
                [int]$state.openings,
                [long]$state.durationMillis,
                [long]$state.sessionCount,
                [bool]$state.hasLiveSession,
                $state.liveInactiveSinceEpochMillis,
                $state.liveEndedAtEpochMillis
            ) -join "|"
            if ($null -ne $previousState -and $fingerprint -eq $previousFingerprint) {
                return $state
            }
            $previousState = $state
            $previousFingerprint = $fingerprint
        } catch {
            $lastError = $_
        }
        if ($attempt -lt 5) {
            Start-Sleep -Milliseconds 250
        }
    }
    if ($null -ne $lastError) {
        throw "Room-Datenbank konnte nicht stabil gelesen werden: $lastError"
    }
    throw "Room-Datenbank änderte sich während aller fünf Snapshot-Versuche."
}

function Send-Home {
    $topPackage = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-Adb -AdbArguments @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
        Start-Sleep -Milliseconds 500
        $topPackage = Get-TopPackage
        if ($topPackage -ne $TargetPackage) {
            return
        }
        if ($attempt -lt 3) {
            Write-Host "  HOME-Retry: Ziel-App lag weiterhin im Vordergrund."
        }
    }
    throw "Nach drei HOME-Versuchen blieb '$TargetPackage' im Vordergrund."
}

function Get-TopPackage {
    $activities = Invoke-Adb -AdbArguments @("shell", "dumpsys", "activity", "activities")
    foreach ($pattern in @(
        "(?m)topResumedActivity=.*?\s(?<package>[A-Za-z0-9_.$]+)/(?:[A-Za-z0-9_.$]+)",
        "(?m)mResumedActivity=.*?\s(?<package>[A-Za-z0-9_.$]+)/(?:[A-Za-z0-9_.$]+)"
    )) {
        $match = [regex]::Match($activities, $pattern)
        if ($match.Success) {
            return $match.Groups["package"].Value
        }
    }
    throw "Die aktuell fortgesetzte Activity konnte nicht aus dumpsys activity ermittelt werden."
}

function Start-TargetApp {
    $topPackage = $null
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $startOutput = Invoke-Adb -AdbArguments @(
            "shell",
            "am",
            "start",
            "-W",
            "-S",
            "-n",
            $script:TargetActivity
        )
        if ($startOutput -notmatch "(?m)^Status:\s*ok\s*$") {
            throw "am start hat die Ziel-App nicht eindeutig gestartet:`n$startOutput"
        }
        Start-Sleep -Milliseconds 500
        $topPackage = Get-TopPackage
        if ($topPackage -eq $TargetPackage) {
            return
        }
        if ($attempt -lt 3) {
            Write-Host "  Start-Retry: '$topPackage' lag noch im Vordergrund."
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Nach drei Startversuchen ist '$topPackage' statt '$TargetPackage' im Vordergrund."
}

function Wait-WhileTargetIsForeground {
    param([Parameter(Mandatory = $true)][int]$Seconds)

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $Seconds) {
        $remainingMillis = [math]::Max(
            1,
            [math]::Min(1000, ($Seconds * 1000) - $stopwatch.ElapsedMilliseconds)
        )
        Start-Sleep -Milliseconds ([int]$remainingMillis)
        $topPackage = Get-TopPackage
        if ($topPackage -ne $TargetPackage) {
            throw "Ziel-App verlor den Vordergrund an '$topPackage'. Das Telefon wurde möglicherweise benutzt."
        }
    }
}

function Wait-WhileTargetIsBackground {
    param([Parameter(Mandatory = $true)][int]$Seconds)

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $Seconds) {
        $remainingMillis = [math]::Max(
            1,
            [math]::Min(500, ($Seconds * 1000) - $stopwatch.ElapsedMilliseconds)
        )
        Start-Sleep -Milliseconds ([int]$remainingMillis)
        $topPackage = Get-TopPackage
        if ($topPackage -eq $TargetPackage) {
            throw (
                "Ziel-App erschien während der erwarteten Hintergrundphase erneut im " +
                "Vordergrund. Das Telefon wurde benutzt oder ein weiterer Runner ist aktiv."
            )
        }
    }
}

function Wait-ForFreshOpening {
    # Force a real non-target window transition first. If HOME is already visible,
    # another HOME key event may not emit an accessibility event and a stale live
    # target session from an interrupted earlier run would otherwise stay active.
    $appLimitStart = Invoke-Adb -AdbArguments @(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "$($script:AppPackage)/.MainActivity"
    )
    if ($appLimitStart -notmatch "(?m)^Status:\s*ok\s*$") {
        throw "AppLimit konnte für die Vorbedingung nicht geöffnet werden:`n$appLimitStart"
    }
    Start-Sleep -Milliseconds 500
    Send-Home
    Start-Sleep -Seconds 1
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $stableFinalizedReads = 0
    $stableSessionCount = $null
    $lastReportedRemainingSeconds = $null

    while ($stopwatch.Elapsed.TotalSeconds -lt $script:FreshnessTimeoutSeconds) {
        $topPackage = Get-TopPackage
        if ($topPackage -eq $TargetPackage) {
            Write-Host "  Vorbedingung zurückgesetzt: Ziel-App wurde erneut geöffnet."
            Send-Home
            $stableFinalizedReads = 0
            $stableSessionCount = $null
            Start-Sleep -Seconds 1
            continue
        }

        $state = Get-RoomState
        if (-not $state.hasLiveSession) {
            if ($stableSessionCount -eq [long]$state.sessionCount) {
                $stableFinalizedReads++
            } else {
                $stableSessionCount = [long]$state.sessionCount
                $stableFinalizedReads = 1
            }
            if ($stableFinalizedReads -ge 2) {
                return
            }
            Start-Sleep -Seconds 1
            continue
        }

        $stableFinalizedReads = 0
        $stableSessionCount = $null
        if ($null -eq $state.liveInactiveSinceEpochMillis) {
            Start-Sleep -Seconds 1
            continue
        }

        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $inactiveFor = $now - [long]$state.liveInactiveSinceEpochMillis
        $remaining = $script:DebounceMillis + 1L - $inactiveFor
        if ($remaining -gt 0) {
            $remainingSeconds = [math]::Ceiling($remaining / 1000.0)
            if ($remainingSeconds -ne $lastReportedRemainingSeconds) {
                Write-Host (
                    "  Vorbedingung: noch {0:N0} s ununterbrochene Ziel-App-Ruhe." -f
                    $remainingSeconds
                )
                $lastReportedRemainingSeconds = $remainingSeconds
            }
            Start-Sleep -Milliseconds ([int][math]::Min(1000L, $remaining))
        } else {
            # Der Ticker finalisiert die abgelaufene Session; erst zwei danach
            # übereinstimmende Snapshots dürfen die Baseline freigeben.
            Start-Sleep -Seconds 1
        }
    }

    throw (
        "Innerhalb von $($script:FreshnessTimeoutSeconds) s entstand keine stabil " +
        "finalisierte Ziel-App-Session. Telefoninteraktion oder ein fremder Start liegt nahe."
    )
}

function Invoke-Scenario {
    param(
        [Parameter(Mandatory = $true)][int]$Number,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][int]$ExpectedOpenings,
        [Parameter(Mandatory = $true)][int]$ExpectedDurationSeconds,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    Write-Host ""
    Write-Host "Szenario ${Number}: $Name" -ForegroundColor Cyan
    try {
        Wait-ForFreshOpening
        $before = Get-RoomState
        if ($before.hasLiveSession) {
            throw "Die Baseline enthält noch eine offene Ziel-App-Session."
        }
        & $Action
        Start-Sleep -Seconds $script:PostFlushSeconds
        $after = Get-RoomState

        $openingDelta = [int]$after.openings - [int]$before.openings
        $sessionDelta = [long]$after.sessionCount - [long]$before.sessionCount
        $durationDelta = [long]$after.durationMillis - [long]$before.durationMillis
        $expectedDurationMillis = $ExpectedDurationSeconds * 1000L
        $toleranceMillis = $DurationToleranceSeconds * 1000L
        $durationPass = [math]::Abs($durationDelta - $expectedDurationMillis) -le $toleranceMillis
        $pass =
            $openingDelta -eq $ExpectedOpenings -and
            $sessionDelta -eq $ExpectedOpenings -and
            $durationPass
        $measurement = (
            "Öffnungen +{0}, Sessions +{1} (je erwartet +{2}), Zeit +{3:N1} s (erwartet ca. {4} s)" -f
            $openingDelta,
            $sessionDelta,
            $ExpectedOpenings,
            ($durationDelta / 1000.0),
            $ExpectedDurationSeconds
        )

        if ($pass) {
            Write-Host "[PASS] $measurement" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] $measurement" -ForegroundColor Red
        }
        return [pscustomobject]@{ Number = $Number; Passed = $pass; Detail = $measurement }
    } catch {
        Send-Home
        $detail = $_.Exception.Message
        Write-Host "[FAIL] $detail" -ForegroundColor Red
        return [pscustomobject]@{ Number = $Number; Passed = $false; Detail = $detail }
    }
}

$script:AdbPath = Resolve-AdbPath
$script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $DeviceSerial
$script:VerificationMutex = Enter-VerificationMutex `
    -Serial $script:DeviceSerial `
    -PackageName $TargetPackage
try {
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $pythonCommand) {
    throw "Python 3 wird für die lokale SQLite-Auswertung benötigt."
}
$script:PythonPath = $pythonCommand.Source
$script:DeviceDate = (Invoke-Adb -AdbArguments @("shell", "date", "+%F")).Trim()

Write-Host "AppLimit Geräte-Verifikation" -ForegroundColor Cyan
Write-Host "Gerät: $($script:DeviceSerial)"
Write-Host "Ziel-App: $TargetPackage"
Write-Warning "Telefon bis zum Ende NICHT benutzen. Fremde Interaktionen verfälschen die Messung."
Write-Host "Screen-off-Szenarien werden bewusst nicht automatisiert."

$resolvedActivity = Invoke-Adb -AdbArguments @(
    "shell",
    "cmd",
    "package",
    "resolve-activity",
    "--brief",
    "-c",
    "android.intent.category.LAUNCHER",
    $TargetPackage
)
if ($resolvedActivity -notmatch [regex]::Escape($TargetPackage)) {
    throw "Für '$TargetPackage' wurde keine Launcher-Activity gefunden: $resolvedActivity"
}
$script:TargetActivity = @(
    $resolvedActivity -split "`r?`n" |
        Where-Object { $_ -match "^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$" }
) | Select-Object -Last 1
if (-not $script:TargetActivity) {
    throw "Die Launcher-Activity konnte nicht aus der Paketauflösung gelesen werden: $resolvedActivity"
}

$enabledServices = Invoke-Adb -AdbArguments @(
    "shell",
    "settings",
    "get",
    "secure",
    "enabled_accessibility_services"
)
if ($enabledServices -notmatch [regex]::Escape($script:AccessibilityService)) {
    throw "Der AppLimit AccessibilityService ist nicht aktiviert."
}

Invoke-Adb -AdbArguments @("shell", "run-as", $script:AppPackage, "true") | Out-Null
$powerState = Invoke-Adb -AdbArguments @("shell", "dumpsys", "power")
if ($powerState -notmatch "mWakefulness=Awake") {
    throw "Das Gerät ist nicht wach. Bitte Bildschirm einschalten und entsperren."
}
$windowPolicy = Invoke-Adb -AdbArguments @("shell", "dumpsys", "window", "policy")
if ($windowPolicy -match "(?m)^\s*(?:showing|mIsShowing)=true\s*$") {
    throw "Der Keyguard ist aktiv. Bitte das Gerät entsperren."
}

$originalScreenTimeout = (
    Invoke-Adb -AdbArguments @("shell", "settings", "get", "system", "screen_off_timeout")
).Trim()
$results = @()

try {
    Invoke-Adb -AdbArguments @(
        "shell",
        "settings",
        "put",
        "system",
        "screen_off_timeout",
        "600000"
    ) | Out-Null

    $scenario1 = @{
        Number = 1
        Name = "Einfache Zeitmessung"
        ExpectedOpenings = 1
        ExpectedDurationSeconds = 30
        Action = {
            Start-TargetApp
            Wait-WhileTargetIsForeground -Seconds 30
            Send-Home
        }
    }
    $results += Invoke-Scenario @scenario1

    $scenario2 = @{
        Number = 2
        Name = "30-s-App-Wechsel bleibt eine Öffnung"
        ExpectedOpenings = 1
        ExpectedDurationSeconds = 20
        Action = {
            Start-TargetApp
            Wait-WhileTargetIsForeground -Seconds 10
            Send-Home
            Wait-WhileTargetIsBackground -Seconds 30
            Start-TargetApp
            Wait-WhileTargetIsForeground -Seconds 10
            Send-Home
        }
    }
    $results += Invoke-Scenario @scenario2

    $scenario3 = @{
        Number = 3
        Name = "61-s-App-Wechsel erzeugt zwei Öffnungen"
        ExpectedOpenings = 2
        ExpectedDurationSeconds = 20
        Action = {
            Start-TargetApp
            Wait-WhileTargetIsForeground -Seconds 10
            Send-Home
            Wait-WhileTargetIsBackground -Seconds 61
            Start-TargetApp
            Wait-WhileTargetIsForeground -Seconds 10
            Send-Home
        }
    }
    $results += Invoke-Scenario @scenario3
} finally {
    Send-Home
    if ($originalScreenTimeout -match "^\d+$") {
        Invoke-Adb -AdbArguments @(
            "shell",
            "settings",
            "put",
            "system",
            "screen_off_timeout",
            $originalScreenTimeout
        ) | Out-Null
    }
}

Write-Host ""
Write-Host "Ergebnisübersicht" -ForegroundColor Cyan
foreach ($result in $results) {
    $label = if ($result.Passed) { "PASS" } else { "FAIL" }
    $color = if ($result.Passed) { "Green" } else { "Red" }
    Write-Host "[$label] Szenario $($result.Number): $($result.Detail)" -ForegroundColor $color
}
Write-Host "Szenario 4 wird bewusst nicht automatisiert und ist manuell abgenommen."

if (@($results | Where-Object { -not $_.Passed }).Count -gt 0) {
    exit 1
}
exit 0
} finally {
    Exit-VerificationMutex -Mutex $script:VerificationMutex
}
