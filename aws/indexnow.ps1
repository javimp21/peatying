# Avisa a los buscadores (Bing, Yandex, Seznam, Naver...) de las páginas de peatying.com con IndexNow,
# para que las lean ya en vez de esperar a pasar por ellas. Lanzarlo después de un despliegue que cambie páginas.
#
# Es el método que Bing recomienda y no tiene cuota diaria (el envío de URLs de bing-check.ps1 -Enviar sí: 100 al día).
#
# Uso:  .\aws\indexnow.ps1                    (las que han cambiado desde el último aviso, según el lastmod del sitemap)
#       .\aws\indexnow.ps1 -Probar            (solo dice cuáles avisaría, sin avisar)
#       .\aws\indexnow.ps1 -Todas             (todas las del sitemap)
#       .\aws\indexnow.ps1 /temas/azucar /    (solo esas)
#
# Lo ya avisado se apunta en aws\indexnow-sent.json (dirección -> lastmod con el que se avisó; no va a git):
# volver a avisar de una página que no ha cambiado no acelera nada.
#
# La clave NO es un secreto: es pública a propósito, en https://peatying.com/<clave>.txt, para demostrar que
# quien avisa es el dueño de la web.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Paths, [switch]$Todas, [switch]$Probar)
$ErrorActionPreference = "Stop"

$key = "3231039a3b87120ce5879e1b69c98462"
$site = "https://peatying.com"
$sitemap = [xml](Get-Content "$PSScriptRoot\..\src\main\resources\static\sitemap.xml" -Raw -Encoding UTF8)
$lastmod = @{}
foreach ($u in $sitemap.urlset.url) { $lastmod[$u.loc] = $u.lastmod }

$sentFile = "$PSScriptRoot\indexnow-sent.json"
$sent = @{}
if (Test-Path $sentFile) {
    (Get-Content $sentFile -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $sent[$_.Name] = $_.Value }
}

if ($Paths) { $urls = @($Paths | ForEach-Object { $site + $_ }) }
elseif ($Todas) { $urls = @($lastmod.Keys | Sort-Object) }
else { $urls = @($lastmod.Keys | Where-Object { $sent[$_] -ne $lastmod[$_] } | Sort-Object) }

if ($urls.Count -eq 0) { Write-Host "Nada que avisar: no ha cambiado ninguna pagina desde el ultimo aviso."; exit 0 }
if ($Probar) {
    $urls | ForEach-Object { Write-Host ("  " + $_.Substring($site.Length)) }
    Write-Host "Avisaria de $($urls.Count) direcciones (no se ha enviado nada)."
    exit 0
}
$body = @{ host = "peatying.com"; key = $key; keyLocation = "$site/$key.txt"; urlList = @($urls) } | ConvertTo-Json
$response = Invoke-WebRequest -Uri "https://api.indexnow.org/indexnow" -Method Post -Body $body `
    -ContentType "application/json; charset=utf-8" -UseBasicParsing
# 200 = recibido; 202 = recibido, la clave aún se está comprobando. Cualquier otro código es un error.
Write-Host "IndexNow: $($response.StatusCode) para $(@($urls).Count) direcciones"
foreach ($u in $urls) { if ($lastmod[$u]) { $sent[$u] = $lastmod[$u] } }
$sent | ConvertTo-Json | Out-File $sentFile -Encoding utf8
