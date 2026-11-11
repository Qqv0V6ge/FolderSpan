# Sources:
# https://docs.chocolatey.org/en-us/create/functions/install-chocolateypackage/
# https://docs.chocolatey.org/en-us/create/functions/get-osarchitecturewidth/
# https://learn.microsoft.com/en-us/windows/win32/msi/standard-installer-command-line-options
# https://learn.microsoft.com/en-us/windows/win32/msi/error-codes
$ErrorActionPreference = 'Stop'

# This draft provides only the verified x64 MSI; no 32-bit fallback is supplied.
# Get-OSArchitectureWidth's ARM64 handling is not a claim about current Windows emulation support.
if ((Get-OSArchitectureWidth) -ne 64) {
  throw 'This FolderSpan package draft provides only the Windows x64 MSI.'
}

$packageArgs = @{
  packageName   = $env:ChocolateyPackageName
  fileType      = 'msi'
  url64bit      = 'https://github.com/Qqv0V6ge/FolderSpan/releases/download/v1.0.1/desktop-windows-x64-no-pro-FolderSpan-1.0.1.msi'
  checksum64    = '3B570C9AA31C0DD20CADCA98D6A695C4198E7AF24D2EEEE6FD0B3A4603B3D8A2'
  checksumType64= 'sha256'
  silentArgs    = '/qn /norestart'
  validExitCodes= @(0, 3010, 1641)
}

# The helper downloads, checks the checksum and invokes the MSI installer.
# ALLUSERS=1 is authored in this MSI; this draft does not change its installation scope.
# Let the MSI create its own shortcuts and let Chocolatey record installed-software changes.
Install-ChocolateyPackage @packageArgs
