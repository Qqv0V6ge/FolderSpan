param([Parameter(Mandatory)][ValidateSet('x64', 'arm64')][string]$Architecture)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$results = New-Item -ItemType Directory -Force "$root/distribution-results"
$work = New-Item -ItemType Directory -Force "$env:RUNNER_TEMP/folderspan-distribution"
$report = [ordered]@{ architecture = $Architecture; checks = @(); scope = 'Installation, upgrade, launch-window and uninstall smoke checks; not full feature certification' }

function Assert-NativeExit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed: exit $LASTEXITCODE" }
    $report.checks += $Step
}

function Download-Package([string]$Version, [string]$Kind) {
    $entry = (Get-Content "$root/packaging/releases.json" -Raw | ConvertFrom-Json).$Version.$Kind
    $path = Join-Path $work $entry.name
    Invoke-WebRequest $entry.url -OutFile $path
    if ((Get-FileHash $path -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Checksum mismatch: $($entry.name)" }
    $report.checks += "SHA256 $($entry.name)"
    return $path
}

function Test-Launch([string]$Executable, [string]$Label) {
    $process = Start-Process $Executable -PassThru -RedirectStandardOutput "$results/$Label-stdout.txt" -RedirectStandardError "$results/$Label-stderr.txt"
    try {
        $deadline = (Get-Date).AddSeconds(60)
        do {
            Start-Sleep -Milliseconds 500
            $process.Refresh()
            if ($process.HasExited) { throw "$Label exited before opening a window: $($process.ExitCode)" }
        } until ($process.MainWindowHandle -ne 0 -or (Get-Date) -gt $deadline)
        if ($process.MainWindowHandle -eq 0 -or !$process.Responding) { throw "$Label did not open a responding desktop window" }
        $report.checks += "$Label responding window: $($process.MainWindowTitle)"
        Add-Type -AssemblyName System.Windows.Forms, System.Drawing
        $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
        $bitmap = [System.Drawing.Bitmap]::new($bounds.Width, $bounds.Height)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
            $bitmap.Save("$results/$Label.png")
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    } finally {
        if (!$process.HasExited) {
            $null = $process.CloseMainWindow()
            if (!$process.WaitForExit(5000)) { Stop-Process -Id $process.Id -Force }
        }
    }
}

function Invoke-Msi([string]$Operation, [string]$Package, [string]$Label) {
    $process = Start-Process msiexec.exe -ArgumentList @($Operation, "`"$Package`"", '/qn', '/norestart', '/L*v', "`"$results/$Label-msi.log`"") -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "$Label failed: MSI exit $($process.ExitCode)" }
    $report.checks += "$Label MSI exit $($process.ExitCode)"
}

function Get-InstalledApp {
    @(Get-ItemProperty 'HKLM:/SOFTWARE/Microsoft/Windows/CurrentVersion/Uninstall/*' -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'FolderSpan' })
}

try {
    if ($Architecture -eq 'x64') {
        if (!(Get-Command winget -ErrorAction SilentlyContinue)) {
            Install-Module Microsoft.WinGet.Client -Repository PSGallery -Force -Scope CurrentUser
            Repair-WinGetPackageManager -AllUsers
        }
        winget validate --manifest "$root/packaging/winget" --disable-interactivity
        Assert-NativeExit 'winget validate'
        $old = Download-Package '1.0.0' 'windows-x64'
        $current = Download-Package '1.0.1' 'windows-x64'
        $exe = "$env:ProgramFiles/FolderSpan/FolderSpan.exe"
        Invoke-Msi '/i' $old 'install-1.0.0'
        if ((Get-InstalledApp).DisplayVersion -ne '1.0.0') { throw 'Old MSI registry version missing' }
        Test-Launch $exe 'msi-1.0.0'
        Invoke-Msi '/i' $current 'upgrade-1.0.1'
        $installed = @(Get-InstalledApp)
        if ($installed.Count -ne 1 -or $installed[0].DisplayVersion -ne '1.0.1') { throw 'Upgrade left an old or duplicate MSI registration' }
        Test-Launch $exe 'msi-1.0.1'
        Invoke-Msi '/x' $current 'uninstall-1.0.1'
        if ((Get-InstalledApp).Count -ne 0 -or (Test-Path $exe)) { throw 'MSI uninstall left application or registration behind' }

        choco pack "$root/packaging/chocolatey/folderspan.nuspec" --output-directory "$work"
        Assert-NativeExit 'choco pack'
        Copy-Item "$work/folderspan.1.0.1.nupkg" $results
        choco install folderspan --version 1.0.1 --source "$work" --yes --no-progress --limit-output
        Assert-NativeExit 'choco install'
        Test-Launch $exe 'chocolatey-1.0.1'
        choco uninstall folderspan --yes --no-progress --limit-output
        Assert-NativeExit 'choco uninstall'
        if ((Get-InstalledApp).Count -ne 0 -or (Test-Path $exe)) { throw 'Chocolatey uninstall left application behind' }
    } else {
        # A real ARM64 runner verifies the ARM64 ZIP; do not test it under x64 emulation.
        $env:SCOOP = "$work/scoop"
        git clone --depth 1 https://github.com/ScoopInstaller/Scoop.git "$env:SCOOP/apps/scoop/current"
        Assert-NativeExit 'clone official Scoop'
        $scoop = "$env:SCOOP/apps/scoop/current/bin/scoop.ps1"
        $bucket = New-Item -ItemType Directory -Force "$work/bucket-source/bucket"
        $repo = Split-Path $bucket -Parent
        $manifest = Get-Content "$root/packaging/scoop/bucket/folderspan.json" -Raw | ConvertFrom-Json
        $oldEntry = (Get-Content "$root/packaging/releases.json" -Raw | ConvertFrom-Json).'1.0.0'.'windows-arm64'
        $oldManifest = Get-Content "$root/packaging/scoop/bucket/folderspan.json" -Raw | ConvertFrom-Json
        $oldManifest.version = '1.0.0'
        $oldManifest.architecture.arm64.url = $oldEntry.url
        $oldManifest.architecture.arm64.hash = $oldEntry.sha256
        $oldManifest | ConvertTo-Json -Depth 8 | Set-Content "$bucket/folderspan.json"
        git -C $repo init
        git -C $repo add bucket
        git -C $repo -c user.name=FolderSpan -c user.email=572169725@qq.com commit -m 'Test fixture 1.0.0'
        Assert-NativeExit 'create local Scoop test bucket'
        & $scoop bucket add folderspan-validation $repo
        Assert-NativeExit 'scoop bucket add'
        & $scoop install folderspan-validation/folderspan
        Assert-NativeExit 'scoop install 1.0.0'
        $exe = "$env:SCOOP/apps/folderspan/current/FolderSpan.exe"
        Test-Launch $exe 'scoop-1.0.0'
        $manifest | ConvertTo-Json -Depth 8 | Set-Content "$bucket/folderspan.json"
        git -C $repo add bucket
        git -C $repo -c user.name=FolderSpan -c user.email=572169725@qq.com commit -m 'Test fixture 1.0.1'
        Assert-NativeExit 'update local Scoop test bucket'
        & $scoop update
        Assert-NativeExit 'scoop update buckets'
        & $scoop update folderspan
        Assert-NativeExit 'scoop upgrade 1.0.1'
        if (!(Test-Path "$env:SCOOP/apps/folderspan/1.0.1/FolderSpan.exe")) { throw 'Scoop did not install version 1.0.1' }
        Test-Launch $exe 'scoop-1.0.1'
        & $scoop uninstall folderspan
        Assert-NativeExit 'scoop uninstall'
        if (Test-Path "$env:SCOOP/apps/folderspan") { throw 'Scoop uninstall left application behind' }
    }
    $report.passed = $true
} catch {
    $report.passed = $false
    $report.error = $_.Exception.Message
    throw
} finally {
    $report | ConvertTo-Json -Depth 8 | Set-Content "$results/windows-$Architecture.json"
}
