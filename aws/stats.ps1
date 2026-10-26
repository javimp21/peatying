# Muestra las métricas de uso de la web (visitas, preguntas, votos, coste), leídas de la base de datos
# de producción. Se ejecuta en el servidor con SSM, igual que deploy.ps1: sin SSH.
#
# Uso:  .\aws\stats.ps1        (si dice que la sesión ha caducado: aws login --remote --region eu-west-1)
$ErrorActionPreference = "Stop"

$aws = "aws"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { $aws = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe" }
. "$PSScriptRoot\config.ps1"      # $instanceId (no está en git: ver config.example.ps1)
# La salida lleva emojis (👍/👎): la CLI de AWS (Python) y la consola tienen que usar UTF-8.
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# La sesión de "aws login" caduca a las pocas horas: lo comprobamos antes para dar un mensaje claro.
try { & $aws sts get-caller-identity 2>$null | Out-Null; $ok = ($LASTEXITCODE -eq 0) } catch { $ok = $false }
if (-not $ok) {
    Write-Host "La sesión de AWS ha caducado. Ejecuta 'aws login --remote --region eu-west-1' y vuelve a lanzar este script."
    exit 1
}

# Lo que se ejecuta en el servidor:
#  - APP_TAG: la MISMA versión que la web en marcha. Sin esto, compose usaría la imagen "latest", que es antigua.
#  - "stats" en un contenedor de un solo uso (--rm) que se borra al terminar.
#  - sed se queda desde la primera tabla ("=== ...") hasta el final: fuera el arranque de Spring.
$remote = @'
cd /opt/ragpeat
export APP_TAG=$(docker inspect ragpeat-app-1 --format '{{.Config.Image}}' | sed 's/.*://')
echo "Version en marcha: $APP_TAG"
docker compose run --rm -T app stats 2>/dev/null | grep -v -E ' INFO | WARN ' | sed -n '/^===/,$p'
'@

$paramsFile = New-TemporaryFile
@{ commands = @($remote -split "`r?`n" | Where-Object { $_ }) } | ConvertTo-Json | Set-Content -Path $paramsFile -Encoding ascii
$commandId = & $aws ssm send-command --instance-ids $instanceId --document-name AWS-RunShellScript `
    --comment "stats" --parameters "file://$paramsFile" --query Command.CommandId --output text
Remove-Item $paramsFile

& $aws ssm wait command-executed --command-id $commandId --instance-id $instanceId 2>$null
# Recogemos la salida en una variable en vez de dejar que la CLI escriba directamente en la consola:
# en la consola de Windows (que no es UTF-8) fallaba con los emojis ("'charmap' codec can't encode").
$report = & $aws ssm get-command-invocation --command-id $commandId --instance-id $instanceId `
    --query StandardOutputContent --output text
$report
