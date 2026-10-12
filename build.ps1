#Requires -Version 5.1
<#
.SYNOPSIS
    StreamEmber çekirdeğini (streamember-core.jar) derler ve Paper sunucusunun plugins klasörüne kopyalar.
.PARAMETER NoCopy
    Yalnız derle (build\libs\streamember-core.jar).
.PARAMETER PaperLibs
    Paper API'yi sunucunun kütüphanelerinden al (internetsiz derleme), ör. D:\Games\MinecaftServer\libraries
.EXAMPLE
    .\build.ps1
#>
[CmdletBinding()]
param([switch]$NoCopy, [string]$PaperLibs)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$gradleArgs = @($(if ($NoCopy) { 'jar' } else { 'deploy' }), '--console=plain')
if ($PaperLibs) { $gradleArgs += "-PpaperLibs=$PaperLibs" }
& (Join-Path $PSScriptRoot 'gradlew.bat') @gradleArgs
if ($LASTEXITCODE -ne 0) { Write-Host "HATA: Gradle derlemesi başarısız ($LASTEXITCODE)." -ForegroundColor Red; exit 1 }
