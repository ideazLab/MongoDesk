# Run only on a disposable Windows CI runner: installs and removes the built MSI.
$ErrorActionPreference = 'Stop'
$packages = @(Get-ChildItem 'build/compose/binaries/main/msi/*.msi')
if ($packages.Count -ne 1) { throw 'Expected exactly one MongoDesk MSI.' }
$msi = $packages[0].FullName
$logs = New-Item -ItemType Directory -Force 'build/windows-install-test'
$version = (Get-Content VERSION -Raw).Trim()
function Invoke-Msi([string]$arguments) {
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "Windows Installer failed: $($process.ExitCode). See MSI logs." }
}
$registryRoots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
function Get-MongoDeskRegistration {
    @(Get-ItemProperty $registryRoots -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'MongoDesk' })
}
if ((Get-MongoDeskRegistration).Count) { throw 'Runner already has MongoDesk installed; refusing to modify it.' }
$installed = $false
try {
    Invoke-Msi "/i `"$msi`" /qn /norestart /L*v `"$($logs.FullName)\install.log`""
    $installed = $true
    $entries = @(Get-MongoDeskRegistration)
    if ($entries.Count -ne 1) { throw 'MongoDesk is missing or duplicated in Programs and Features.' }
    $entry = $entries[0]
    if ($entry.DisplayVersion -ne $version -or $entry.Publisher -ne 'Ideazlab') { throw 'Incorrect installed app metadata.' }
    if ($entry.SystemComponent -eq 1 -or -not $entry.UninstallString) { throw 'App is hidden or has no uninstall command.' }
    $exe = Join-Path $env:ProgramFiles 'MongoDesk\MongoDesk.exe'
    if (-not (Test-Path $exe)) { throw "Missing application launcher: $exe" }
    $links = @(
        (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'MongoDesk\MongoDesk.lnk'),
        (Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'MongoDesk.lnk')
    )
    $shell = New-Object -ComObject WScript.Shell
    foreach ($link in $links) {
        if (-not (Test-Path $link)) { throw "Missing shortcut: $link" }
        if ($shell.CreateShortcut($link).TargetPath -ne $exe) { throw "Shortcut points to the wrong launcher: $link" }
    }
    Write-Host "Verified MongoDesk ${version}: installed apps registration, Start menu, desktop shortcut, and executable."
} finally {
    if ($installed) {
        Invoke-Msi "/x `"$msi`" /qn /norestart /L*v `"$($logs.FullName)\uninstall.log`""
        if ((Get-MongoDeskRegistration).Count) { throw 'Uninstall left a Programs and Features entry.' }
        foreach ($link in $links) {
            if ($link -and (Test-Path $link)) { throw "Uninstall left shortcut: $link" }
        }
    }
}
