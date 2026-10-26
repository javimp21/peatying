# Abre el panel privado (peatying.com/panel) en el navegador, ya con su clave.
# La clave está cifrada en Parameter Store (/ragpeat/admin-token): este script la lee con tu sesión de AWS
# y se la pasa a la página tras "#", que nunca se envía al servidor. La página la recuerda en este navegador.
#
# Uso:  .\aws\panel.ps1        (si dice que la sesión ha caducado: aws login --remote --region eu-west-1)
#       .\aws\panel.ps1 -Movil (muestra un código QR para abrirlo en el móvil con la cámara)
#       .\aws\panel.ps1 -Local (contra http://localhost:8080, para probar)
param([switch]$Local, [switch]$Movil)
$ErrorActionPreference = "Stop"

$aws = "aws"
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { $aws = "$env:LOCALAPPDATA\Programs\Amazon\AWSCLIV2\aws.exe" }
try { $key = & $aws ssm get-parameter --name /ragpeat/admin-token --with-decryption --query Parameter.Value --output text 2>$null } catch { $key = $null }
if (-not $key) {
    Write-Host "No se pudo leer la clave. Si la sesión de AWS ha caducado: aws login --remote --region eu-west-1"
    exit 1
}
$base = if ($Local) { "http://localhost:8080" } else { "https://peatying.com" }

if ($Movil) {
    # "&movil": la página deja la clave en la dirección. En el iPhone, "Añadir a pantalla de inicio" guarda la
    # dirección tal cual y esa "app" no comparte datos con Safari: sin la clave en la dirección, la perdería.
    $url = "$base/panel#t=$([uri]::EscapeDataString($key))&movil"
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    # La dirección va por la entrada estándar, no como argumento: así no queda en la lista de procesos.
    $url | python -c "import sys, qrcode; q = qrcode.QRCode(border=2); q.add_data(sys.stdin.read().strip()); q.print_ascii(invert=True)"
    if ($LASTEXITCODE -ne 0) { Write-Host "Hace falta la librería qrcode: python -m pip install --user qrcode"; exit 1 }
    Write-Host "`nEscanéalo con la cámara del móvil. En el iPhone, para tenerlo como app: Compartir -> Añadir a pantalla de inicio."
    Write-Host "Es tu clave: no compartas ni fotografíes este código."
    exit 0
}
Start-Process "$base/panel#t=$([uri]::EscapeDataString($key))"
