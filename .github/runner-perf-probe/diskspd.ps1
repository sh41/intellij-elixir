# DiskSpd random 4K and sequential 1M, read and write, against a preconditioned file in each target
# directory. -o is the queue depth per thread, so 4 threads at -o32 keep 128 I/Os in flight.
param(
    [Parameter(Mandatory)][string[]]$Targets,
    [Parameter(Mandatory)][string]$OutDir,
    [int]$FileSizeMiB = 1024,
    [int]$Duration = 20,
    [int]$Warmup = 5
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $OutDir | Out-Null

$zip = Join-Path $OutDir 'DiskSpd.zip'
Invoke-WebRequest 'https://github.com/microsoft/diskspd/releases/download/v2.3/DiskSpd.ZIP' -OutFile $zip
$sha = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sha -ne '18609981e8b04b4f645f1fd182ad0541615fe71722de36a9a933c54f747218a3') {
    throw "DiskSpd.ZIP sha256 $sha does not match the v2.3 release asset digest"
}
Expand-Archive $zip (Join-Path $OutDir 'diskspd') -Force
$exe = Join-Path $OutDir 'diskspd\amd64\diskspd.exe'

$tests = [ordered]@{
    'rand 4K read, 1 thread QD1'     = '-b4K -r -w0 -t1 -o1'
    'rand 4K write, 1 thread QD1'    = '-b4K -r -w100 -t1 -o1'
    'rand 4K read, 4 threads QD32'   = '-b4K -r -w0 -t4 -o32'
    'rand 4K write, 4 threads QD32'  = '-b4K -r -w100 -t4 -o32'
    'seq 1M read, 1 thread QD8'      = '-b1M -w0 -t1 -o8'
    'seq 1M write, 1 thread QD8'     = '-b1M -w100 -t1 -o8'
}

$fills = @()
$rows = foreach ($dir in $Targets) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $file = Join-Path $dir 'diskspd.dat'

    # A cloud disk can answer reads of never-written blocks without touching storage, so write real
    # data over the whole file before reading it back.
    $buf = [byte[]]::new(1MB)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $fs = [IO.FileStream]::new($file, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None, 1MB)
    try {
        for ($i = 0; $i -lt $FileSizeMiB; $i++) {
            [Security.Cryptography.RandomNumberGenerator]::Fill($buf)
            $fs.Write($buf, 0, $buf.Length)
        }
        $fs.Flush($true)
    } finally {
        $fs.Dispose()
    }
    $fill = '{0}: wrote {1} MiB of random data and flushed in {2:N1} s ({3:N0} MiB/s)' -f $dir, $FileSizeMiB, $sw.Elapsed.TotalSeconds, ($FileSizeMiB / $sw.Elapsed.TotalSeconds)
    Write-Host $fill
    $fills += $fill

    foreach ($name in $tests.Keys) {
        $xmlPath = Join-Path $OutDir ('{0}{1}.xml' -f ($dir -replace '[:\\]+', '_'), ($name -replace '[^A-Za-z0-9]+', '-'))
        $dsArgs = @($tests[$name] -split ' ') + @("-d$Duration", "-W$Warmup", '-Sh', '-L', '-Z1M', '-Rxml', $file)
        & $exe @dsArgs | Out-File $xmlPath -Encoding utf8
        if ($LASTEXITCODE) { throw "diskspd exited $LASTEXITCODE for '$name' on $dir" }

        [xml]$x = Get-Content $xmlPath -Raw
        $ts = $x.Results.TimeSpan
        $secs = [double]$ts.TestTimeSeconds
        $targetNodes = @($ts.Thread | ForEach-Object { $_.Target })
        $bytes = ($targetNodes | ForEach-Object { [double]$_.ReadBytes + [double]$_.WriteBytes } | Measure-Object -Sum).Sum
        $ios = ($targetNodes | ForEach-Object { [double]$_.ReadCount + [double]$_.WriteCount } | Measure-Object -Sum).Sum
        $buckets = @($ts.Latency.Bucket)
        $pct = { param($p) ($buckets | Where-Object { [double]$_.Percentile -eq $p } | Select-Object -First 1).TotalMilliseconds }
        $row = [pscustomobject]@{
            Target     = $dir
            Test       = $name
            IOPS       = [math]::Round($ios / $secs)
            'MiB/s'    = [math]::Round($bytes / 1MB / $secs, 1)
            'p50 ms'   = & $pct 50
            'p99 ms'   = & $pct 99
            'p99.9 ms' = & $pct 99.9
        }
        Write-Host ($row | Format-List | Out-String).Trim()
        $row
    }
    Remove-Item $file -Force
}

$rows | Export-Csv (Join-Path $OutDir 'diskspd.csv') -NoTypeInformation
$rows | Format-Table -AutoSize | Out-String -Width 220 | Write-Host

if ($env:GITHUB_STEP_SUMMARY) {
    $md = @(
        '### DiskSpd', '',
        "$FileSizeMiB MiB preconditioned file, $Duration s measured after $Warmup s warm-up, ``-Sh`` (no OS cache, write-through), ``-Z1M`` random write buffer.", ''
    ) + ($fills | ForEach-Object { "- $_" }) + @('',
        '| target | test | IOPS | MiB/s | p50 ms | p99 ms | p99.9 ms |',
        '| --- | --- | ---: | ---: | ---: | ---: | ---: |'
    ) + ($rows | ForEach-Object { "| $($_.Target) | $($_.Test) | $($_.IOPS) | $($_.'MiB/s') | $($_.'p50 ms') | $($_.'p99 ms') | $($_.'p99.9 ms') |" })
    ($md -join "`n") + "`n" | Add-Content $env:GITHUB_STEP_SUMMARY
}
