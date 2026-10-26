# Crea (una sola vez) la clave del panel privado: 32 bytes al azar, cifrada en Parameter Store como
# /ragpeat/admin-token. No la muestra en pantalla. Después hay que desplegar para que el servidor la lea:
#   .\aws\create-panel-key.ps1
#   .\aws\deploy.ps1 -Tag <versión en marcha>     (o el siguiente despliegue normal)
# Para cambiarla (si se filtrara): .\aws\create-panel-key.ps1 -Rotate, y desplegar.
param([switch]$Rotate)
$ErrorActionPreference = "Stop"

$aws = "aws"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { $aws = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe" }
$exists = $null
try { $exists = & $aws ssm get-parameter --name /ragpeat/admin-token --query Parameter.Name --output text 2>$null } catch { }
if ($exists -and -not $Rotate) {
    Write-Host "La clave ya existe. Para cambiarla: .\aws\create-panel-key.ps1 -Rotate"
    exit 0
}
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$key = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
& $aws ssm put-parameter --name /ragpeat/admin-token --type SecureString --value $key --overwrite `
    --description "Clave del panel privado de peatying.com" --query Version --output text | Out-Null
Write-Host "Clave del panel guardada en Parameter Store. Despliega para que el servidor la use."
