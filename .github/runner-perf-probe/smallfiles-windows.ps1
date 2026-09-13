# Runs smallfiles.py on C: (the profile TEMP every JVM uses unless overridden), on D: (RUNNER_TEMP) and,
# when setup-dev-drive has run, on the Dev Drive.
# The C: path is built from USERPROFILE because TEMP holds the 8.3 form (C:\Users\RUNNER~1\...).
param(
    [Parameter(Mandatory)][string]$Out,
    [int]$Runs = 3,
    [int]$Files = 20000
)
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'smallfiles.py'
$locations = [ordered]@{
    'C: TEMP'        = Join-Path $env:USERPROFILE 'AppData\Local\Temp'
    'D: RUNNER_TEMP' = $env:RUNNER_TEMP
}
if ($env:DEV_DRIVE) { $locations['Dev Drive'] = Join-Path $env:DEV_DRIVE 'probe' }
foreach ($label in $locations.Keys) {
    Write-Host "$label = $($locations[$label])"
    & python $script --out $Out --label $label --parent $locations[$label] --runs $Runs --files $Files
    if ($LASTEXITCODE) { throw "smallfiles.py failed for $label" }
}
