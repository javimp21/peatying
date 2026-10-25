# Muestra las métricas de uso de la web (visitas, preguntas, votos, coste), leídas de la base de datos
# de producción. Se ejecuta en el servidor con SSM, igual que deploy.ps1: sin SSH.
#
# Uso:  .\aws\stats.ps1        (si dice que la sesión ha caducado: aws login)
$ErrorActionPreference = "Stop"

$aws = "aws"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { $aws = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe" }
$instanceId = "i-0a296d03feb87d054"

# La sesión de "aws login" caduca a las pocas horas: lo comprobamos antes para dar un mensaje claro.
try { & $aws sts get-caller-identity 2>$null | Out-Null; $ok = ($LASTEXITCODE -eq 0) } catch { $ok = $false }
if (-not $ok) {
    Write-Host "La sesión de AWS ha caducado. Ejecuta 'aws login' y vuelve a lanzar este script."
    exit 1
}

# El comando "stats" de la app, en un contenedor de un solo uso (--rm) que se borra al terminar.
# grep quita las líneas de arranque de Spring para dejar solo las tablas.
$paramsFile = New-TemporaryFile
@{ commands = @("cd /opt/ragpeat && docker compose run --rm -T app stats 2>/dev/null | grep -v -E ' INFO | WARN |^\s*$|:: Spring|^ +[/\\(=]|^ *'' *\|'") } |
    ConvertTo-Json | Set-Content -Path $paramsFile -Encoding ascii
$commandId = & $aws ssm send-command --instance-ids $instanceId --document-name AWS-RunShellScript `
    --comment "stats" --parameters "file://$paramsFile" --query Command.CommandId --output text
Remove-Item $paramsFile

& $aws ssm wait command-executed --command-id $commandId --instance-id $instanceId 2>$null
& $aws ssm get-command-invocation --command-id $commandId --instance-id $instanceId `
    --query StandardOutputContent --output text
