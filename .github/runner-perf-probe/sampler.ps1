# Samples memory, paging, per-drive I/O, CPU and Defender CPU every few seconds, plus the resident
# and private memory of each JVM and BEAM kind. Writes long-format rows: epoch,metric,value.
# Cumulative counters are written raw; summarize_samples.py takes per-phase deltas.
# Creating <Dir>\stop ends the loop after one final sample, so no row is cut off by a kill.
param(
    [Parameter(Mandatory)][string]$Dir,
    [int]$Interval = 5
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force $Dir | Out-Null
$csv = Join-Path $Dir 'samples.csv'
$procLog = Join-Path $Dir 'processes.txt'
$stopFile = Join-Path $Dir 'stop'
$me = [Diagnostics.Process]::GetCurrentProcess()
$seen = @{}
$prevCpu = $null

function Get-Kind([string]$Name, [string]$Cmd) {
    if ($Name -match '^(erl|werl|beam|epmd)') { return 'beam' }
    if ($Cmd -match 'GradleDaemon') { return 'gradle-daemon' }
    if ($Cmd -match 'KotlinCompileDaemon') { return 'kotlin-daemon' }
    if ($Cmd -match 'GradleWorkerMain') { return 'gradle-worker' }
    if ($Cmd -match 'GradleWrapperMain') { return 'gradle-client' }
    return 'java-other'
}

function Get-Xmx([string]$Cmd) {
    $m = [regex]::Match($Cmd, '-Xmx\S+')
    if ($m.Success) { return $m.Value }
    # Gradle hands long JVM command lines to java.exe as an @argfile on Windows.
    foreach ($a in [regex]::Matches($Cmd, '@"?([^"\s]+)')) {
        $path = $a.Groups[1].Value
        if (Test-Path -LiteralPath $path) {
            $m = [regex]::Match((Get-Content -LiteralPath $path -Raw), '-Xmx\S+')
            if ($m.Success) { return "$($m.Value) (from argfile)" }
        }
    }
    return 'none'
}

while ($true) {
    $t = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $rows = [Collections.Generic.List[string]]::new()
    try {
        $mem = Get-CimInstance Win32_PerfRawData_PerfOS_Memory
        $rows.Add("$t,avail_mb,$($mem.AvailableMBytes)")
        $rows.Add("$t,commit_mb,$([math]::Round($mem.CommittedBytes / 1MB))")
        $rows.Add("$t,commit_limit_mb,$([math]::Round($mem.CommitLimit / 1MB))")
        $rows.Add("$t,pages_in,$($mem.PagesInputPersec)")
        $rows.Add("$t,pages_out,$($mem.PagesOutputPersec)")
        $pf = (Get-CimInstance Win32_PageFileUsage | Measure-Object CurrentUsage -Sum).Sum
        $rows.Add("$t,pagefile_used_mb,$pf")

        foreach ($d in Get-CimInstance Win32_PerfRawData_PerfDisk_LogicalDisk | Where-Object Name -match '^[A-Z]:$') {
            $rows.Add("$t,disk_read_bytes:$($d.Name),$($d.DiskReadBytesPersec)")
            $rows.Add("$t,disk_write_bytes:$($d.Name),$($d.DiskWriteBytesPersec)")
            $rows.Add("$t,disk_reads:$($d.Name),$($d.DiskReadsPersec)")
            $rows.Add("$t,disk_writes:$($d.Name),$($d.DiskWritesPersec)")
        }

        $cpu = Get-CimInstance Win32_PerfRawData_PerfOS_Processor -Filter "Name='_Total'"
        if ($prevCpu) {
            $dt = [double]$cpu.Timestamp_Sys100NS - [double]$prevCpu.Timestamp_Sys100NS
            if ($dt -gt 0) {
                $busy = 100 * (1 - ([double]$cpu.PercentProcessorTime - [double]$prevCpu.PercentProcessorTime) / $dt)
                $rows.Add("$t,cpu_pct,$([math]::Round([math]::Max(0, $busy), 1))")
            }
        }
        $prevCpu = $cpu
        $rows.Add("$t,proc_queue,$((Get-CimInstance Win32_PerfRawData_PerfOS_System).ProcessorQueueLength)")

        # WMI answers these queries in WmiPrvSE, so the sampler's cost is mostly there rather than in itself.
        $cpuByName = @{ MsMpEng = 0.0; WmiPrvSE = 0.0 }
        foreach ($p in Get-CimInstance Win32_PerfRawData_PerfProc_Process -Filter "Name='MsMpEng' OR Name LIKE 'WmiPrvSE%'") {
            $cpuByName[($p.Name -replace '#\d+$', '')] += [double]$p.PercentProcessorTime / 1e7
        }
        foreach ($k in $cpuByName.Keys) { $rows.Add("$t,cpu_s:$k,$([math]::Round($cpuByName[$k], 1))") }
        $me.Refresh()
        $rows.Add("$t,cpu_s:sampler,$([math]::Round($me.TotalProcessorTime.TotalSeconds, 1))")

        $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='erl.exe' OR Name='werl.exe' OR Name='epmd.exe' OR Name LIKE 'beam%'"
        $byKind = @{}
        foreach ($p in $procs) {
            $kind = Get-Kind $p.Name $p.CommandLine
            if (-not $byKind.ContainsKey($kind)) { $byKind[$kind] = @(0, 0, 0) }
            $byKind[$kind][0] += [double]$p.WorkingSetSize
            $byKind[$kind][1] += [double]$p.PrivatePageCount
            $byKind[$kind][2] += 1
            if (-not $seen.ContainsKey($p.ProcessId)) {
                $seen[$p.ProcessId] = $true
                $cmd = "$($p.CommandLine)"
                Add-Content $procLog ("{0} pid={1} kind={2} xmx={3} cmd={4}" -f $t, $p.ProcessId, $kind, (Get-Xmx $cmd), $cmd.Substring(0, [math]::Min(600, $cmd.Length)))
            }
        }
        foreach ($k in $byKind.Keys) {
            $rows.Add("$t,rss_mb:$k,$([math]::Round($byKind[$k][0] / 1MB))")
            $rows.Add("$t,private_mb:$k,$([math]::Round($byKind[$k][1] / 1MB))")
            $rows.Add("$t,count:$k,$($byKind[$k][2])")
        }
    } catch {
        Add-Content (Join-Path $Dir 'sampler-errors.txt') "$t $_"
    }
    Add-Content $csv $rows
    if (Test-Path -LiteralPath $stopFile) { break }
    Start-Sleep -Seconds $Interval
}
