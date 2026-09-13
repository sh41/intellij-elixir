# CPU, memory, paging, storage and Defender state of a hosted Windows runner.
param([Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$facts = [ordered]@{}

function Section([string]$Title, [scriptblock]$Body) {
    Write-Host "::group::$Title"
    try {
        $value = & $Body
        $facts[$Title] = $value
        $value | Format-List * | Out-String -Width 250 | Write-Host
    } catch {
        Write-Host "FAILED: $_"
        $facts[$Title] = "FAILED: $_"
    }
    Write-Host '::endgroup::'
}

Section 'Processor' { Get-CimInstance Win32_Processor | Select-Object Name, Manufacturer, NumberOfCores, NumberOfLogicalProcessors, MaxClockSpeed, L2CacheSize, L3CacheSize }
Section 'Environment.ProcessorCount' { [pscustomobject]@{ ProcessorCount = [Environment]::ProcessorCount } }
Section 'ComputerSystem' { Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer, Model, NumberOfProcessors, NumberOfLogicalProcessors, @{ n = 'TotalPhysicalMemoryGiB'; e = { [math]::Round($_.TotalPhysicalMemory / 1GB, 2) } }, HypervisorPresent, AutomaticManagedPagefile }
Section 'OperatingSystem' { Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber, @{ n = 'TotalVisibleMemoryMiB'; e = { [math]::Round($_.TotalVisibleMemorySize / 1024) } }, @{ n = 'FreePhysicalMemoryMiB'; e = { [math]::Round($_.FreePhysicalMemory / 1024) } }, @{ n = 'TotalVirtualMemoryMiB'; e = { [math]::Round($_.TotalVirtualMemorySize / 1024) } }, @{ n = 'FreeVirtualMemoryMiB'; e = { [math]::Round($_.FreeVirtualMemory / 1024) } }, @{ n = 'SizeStoredInPagingFilesMiB'; e = { [math]::Round($_.SizeStoredInPagingFiles / 1024) } } }
Section 'PageFileUsage' { Get-CimInstance Win32_PageFileUsage | Select-Object Name, AllocatedBaseSize, CurrentUsage, PeakUsage }
Section 'PageFileSetting' { Get-CimInstance Win32_PageFileSetting | Select-Object Name, InitialSize, MaximumSize }
Section 'Memory commit' { Get-CimInstance Win32_PerfRawData_PerfOS_Memory | Select-Object @{ n = 'CommittedMiB'; e = { [math]::Round($_.CommittedBytes / 1MB) } }, @{ n = 'CommitLimitMiB'; e = { [math]::Round($_.CommitLimit / 1MB) } }, AvailableMBytes }
Section 'PhysicalDisk' { Get-PhysicalDisk | Select-Object DeviceId, FriendlyName, Model, MediaType, BusType, SpindleSpeed, @{ n = 'SizeGiB'; e = { [math]::Round($_.Size / 1GB) } }, LogicalSectorSize, PhysicalSectorSize, HealthStatus }
Section 'Disk' { Get-Disk | Select-Object Number, FriendlyName, BusType, PartitionStyle, IsBoot, IsSystem, ProvisioningType, @{ n = 'SizeGiB'; e = { [math]::Round($_.Size / 1GB) } } }
Section 'StorageAdvancedProperty' { Get-PhysicalDisk | ForEach-Object { $p = Get-StorageAdvancedProperty -PhysicalDisk $_; [pscustomobject]@{ DeviceId = $_.DeviceId; IsDeviceCacheEnabled = $p.IsDeviceCacheEnabled; IsPowerProtected = $p.IsPowerProtected } } }
Section 'Partition' { Get-Partition | Select-Object DiskNumber, PartitionNumber, DriveLetter, @{ n = 'SizeGiB'; e = { [math]::Round($_.Size / 1GB, 1) } }, Type, GptType }
Section 'Volume' { Get-Volume | Select-Object DriveLetter, FileSystemLabel, FileSystem, DriveType, AllocationUnitSize, @{ n = 'SizeGiB'; e = { [math]::Round($_.Size / 1GB) } }, @{ n = 'FreeGiB'; e = { [math]::Round($_.SizeRemaining / 1GB) } }, HealthStatus }
Section 'Volume to disk' { Get-Partition | Where-Object DriveLetter | ForEach-Object { $d = Get-Disk -Number $_.DiskNumber; [pscustomobject]@{ Drive = "$($_.DriveLetter):"; Disk = $_.DiskNumber; FriendlyName = $d.FriendlyName; BusType = $d.BusType; MediaType = (Get-PhysicalDisk | Where-Object DeviceId -eq "$($_.DiskNumber)").MediaType } } }
Section 'fsutil' { [pscustomobject]@{ '8dot3 C:' = (fsutil 8dot3name query C:) -join ' '; '8dot3 D:' = (fsutil 8dot3name query D:) -join ' '; DisableLastAccess = (fsutil behavior query disablelastaccess) -join ' '; DisableDeleteNotify = (fsutil behavior query disabledeletenotify) -join ' ' } }
Section 'MpComputerStatus' { Get-MpComputerStatus | Select-Object AMRunningMode, AMServiceEnabled, AntivirusEnabled, RealTimeProtectionEnabled, OnAccessProtectionEnabled, IoavProtectionEnabled, BehaviorMonitorEnabled, IsTamperProtected, AMProductVersion, AMEngineVersion }
Section 'MpPreference' { Get-MpPreference | Select-Object DisableRealtimeMonitoring, DisableBehaviorMonitoring, DisableIOAVProtection, DisableScriptScanning, DisableArchiveScanning, RealTimeScanDirection, EnableLowCpuPriority, ScanAvgCPULoadFactor, EnableControlledFolderAccess, PUAProtection, MAPSReporting, SubmitSamplesConsent, ExclusionPath, ExclusionProcess, ExclusionExtension }
Section 'Minifilter instances' { [pscustomobject]@{ fltmc = (fltmc instances) -join "`n" } }
Section 'Services' { Get-Service WinDefend, WdFilter, WSearch, SysMain -ErrorAction SilentlyContinue | Select-Object Name, Status, StartType }
Section 'Power scheme' { [pscustomobject]@{ Active = (powercfg /getactivescheme) -join ' ' } }
Section 'Paths' { [pscustomobject]@{ TEMP = $env:TEMP; TMP = $env:TMP; GetTempPath = [IO.Path]::GetTempPath(); USERPROFILE = $env:USERPROFILE; LOCALAPPDATA = $env:LOCALAPPDATA; RUNNER_TEMP = $env:RUNNER_TEMP; RUNNER_TOOL_CACHE = $env:RUNNER_TOOL_CACHE; GITHUB_WORKSPACE = $env:GITHUB_WORKSPACE; ImageOS = $env:ImageOS; ImageVersion = $env:ImageVersion } }

$facts | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir 'inventory-windows.json')

if ($env:GITHUB_STEP_SUMMARY) {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $cs = Get-CimInstance Win32_ComputerSystem
    $mp = Get-MpComputerStatus
    $pref = Get-MpPreference
    $pf = Get-CimInstance Win32_PageFileUsage
    $lines = @(
        '### Windows runner inventory', '',
        "- Image: $env:ImageOS $env:ImageVersion",
        "- CPU: $($cpu.Name); $($cpu.NumberOfCores) cores, $($cpu.NumberOfLogicalProcessors) logical; ProcessorCount $([Environment]::ProcessorCount)",
        "- RAM: $([math]::Round($cs.TotalPhysicalMemory / 1GB, 2)) GiB; page files: $(($pf | ForEach-Object { "$($_.Name) $($_.AllocatedBaseSize) MiB" }) -join ', ')",
        "- Disks: $((Get-PhysicalDisk | ForEach-Object { "#$($_.DeviceId) $($_.FriendlyName) $($_.MediaType)/$($_.BusType) $([math]::Round($_.Size / 1GB)) GiB" }) -join '; ')",
        "- Volumes: $((Get-Partition | Where-Object DriveLetter | ForEach-Object { "$($_.DriveLetter): on disk $($_.DiskNumber)" }) -join ', ')",
        "- Defender: RealTimeProtectionEnabled=$($mp.RealTimeProtectionEnabled), AMRunningMode=$($mp.AMRunningMode), IsTamperProtected=$($mp.IsTamperProtected)",
        "- Defender exclusions: paths [$($pref.ExclusionPath -join '; ')], processes [$($pref.ExclusionProcess -join '; ')], extensions [$($pref.ExclusionExtension -join '; ')]", ''
    )
    ($lines -join "`n") | Add-Content $env:GITHUB_STEP_SUMMARY
}
