# Files under C: created or modified since the job started, grouped by directory. Complements the
# per-drive byte counters, which also see files that were written and deleted again.
param(
    [Parameter(Mandatory)][long]$SinceEpoch,
    [Parameter(Mandatory)][string]$OutDir,
    [string[]]$Roots = @('C:\Users', 'C:\hostedtoolcache', 'C:\Windows\Temp', 'C:\ProgramData'),
    [int]$Depth = 6,
    [int]$Top = 30
)
$ErrorActionPreference = 'Continue'
$since = [DateTimeOffset]::FromUnixTimeSeconds($SinceEpoch).UtcDateTime
$opts = [IO.EnumerationOptions]::new()
$opts.RecurseSubdirectories = $true
$opts.IgnoreInaccessible = $true
$opts.AttributesToSkip = [IO.FileAttributes]::ReparsePoint

$groups = @{}
$sw = [Diagnostics.Stopwatch]::StartNew()
$scanned = 0
foreach ($root in $Roots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    foreach ($f in [IO.DirectoryInfo]::new($root).EnumerateFiles('*', $opts)) {
        $scanned++
        try {
            if ($f.LastWriteTimeUtc -lt $since -and $f.CreationTimeUtc -lt $since) { continue }
            $parts = $f.DirectoryName.Split('\')
            $key = [string]::Join('\', $parts, 0, [math]::Min($Depth, $parts.Length))
            if (-not $groups.ContainsKey($key)) { $groups[$key] = [pscustomobject]@{ Directory = $key; Files = 0; MiB = 0.0 } }
            $groups[$key].Files++
            $groups[$key].MiB += $f.Length / 1MB
        } catch {
            continue
        }
    }
}
$all = @($groups.Values | ForEach-Object { $_.MiB = [math]::Round($_.MiB, 1); $_ })
$totalFiles = ($all | Measure-Object Files -Sum).Sum
$totalMiB = [math]::Round(($all | Measure-Object MiB -Sum).Sum, 1)
Write-Host ("Scanned {0:N0} files under {1} in {2:N0} s; {3:N0} files ({4:N1} MiB) written since {5:u}" -f $scanned, ($Roots -join ', '), $sw.Elapsed.TotalSeconds, $totalFiles, $totalMiB, $since)
$all | Sort-Object MiB -Descending | Export-Csv (Join-Path $OutDir 'c-drive-writes.csv') -NoTypeInformation
$byBytes = $all | Sort-Object MiB -Descending | Select-Object -First $Top
$byCount = $all | Sort-Object Files -Descending | Select-Object -First $Top
Write-Host '--- by size'; $byBytes | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
Write-Host '--- by file count'; $byCount | Format-Table -AutoSize | Out-String -Width 250 | Write-Host

if ($env:GITHUB_STEP_SUMMARY) {
    $md = @('### Files written to C: during the job', '',
        ("{0:N0} files, {1:N1} MiB under {2}, created or modified since {3:u}." -f $totalFiles, $totalMiB, ($Roots -join ', '), $since), '',
        '| directory | files | MiB |', '| --- | ---: | ---: |') +
        ($byBytes | ForEach-Object { "| ``$($_.Directory)`` | $($_.Files) | $($_.MiB) |" })
    ($md -join "`n") + "`n" | Add-Content $env:GITHUB_STEP_SUMMARY
}
