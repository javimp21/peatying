# Abre el panel privado (peatying.com/panel) en el navegador, ya con su clave.
# La clave está cifrada en Parameter Store (/ragpeat/admin-token): este script la lee con tu sesión de AWS
# y se la pasa a la página tras "#", que nunca se envía al servidor. La página la recuerda en este navegador.
#
# Uso:  .\aws\panel.ps1        (si dice que la sesión ha caducado: aws login --remote --region eu-west-1)
#       .\aws\panel.ps1 -Local (contra http://localhost:8080, para probar)
param([switch]$Local)
$ErrorActionPreference = "Stop"

$aws = "aws"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { $aws = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe" }
try { $key = & $aws ssm get-parameter --name /ragpeat/admin-token --with-decryption --query Parameter.Value --output text 2>$null } catch { $key = $null }
if (-not $key) {
    Write-Host "No se pudo leer la clave. Si la sesión de AWS ha caducado: aws login --remote --region eu-west-1"
    exit 1
}
$base = if ($Local) { "http://localhost:8080" } else { "https://peatying.com" }
Start-Process "$base/panel#t=$([uri]::EscapeDataString($key))"
