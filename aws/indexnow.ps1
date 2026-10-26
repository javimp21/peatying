# Avisa a los buscadores (Bing, Yandex, Seznam, Naver...) de las páginas de peatying.com con IndexNow,
# para que las lean ya en vez de esperar a pasar por ellas. Lanzarlo después de un despliegue que cambie páginas.
#
# Uso:  .\aws\indexnow.ps1                    (todas las del sitemap)
#       .\aws\indexnow.ps1 /temas/azucar /     (solo esas)
#
# La clave NO es un secreto: es pública a propósito, en https://peatying.com/<clave>.txt, para demostrar que
# quien avisa es el dueño de la web.
param([string[]]$Paths)
$ErrorActionPreference = "Stop"

$key = "3231039a3b87120ce5879e1b69c98462"
$site = "https://peatying.com"
if ($Paths) {
    $urls = $Paths | ForEach-Object { $site + $_ }
} else {
    $sitemap = [xml](Get-Content "$PSScriptRoot\..\src\main\resources\static\sitemap.xml" -Raw -Encoding UTF8)
    $urls = $sitemap.urlset.url | ForEach-Object { $_.loc }
}
$body = @{ host = "peatying.com"; key = $key; keyLocation = "$site/$key.txt"; urlList = @($urls) } | ConvertTo-Json
$response = Invoke-WebRequest -Uri "https://api.indexnow.org/indexnow" -Method Post -Body $body `
    -ContentType "application/json; charset=utf-8" -UseBasicParsing
# 200 = recibido; 202 = recibido, la clave aún se está comprobando. Cualquier otro código es un error.
Write-Host "IndexNow: $($response.StatusCode) para $(@($urls).Count) direcciones"
