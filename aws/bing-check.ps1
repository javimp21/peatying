# Pregunta a Bing, página por página, si ha leído las direcciones del sitemap y cuándo, y lo compara con el
# lastmod de cada una: así se ve qué páginas no conoce, cuáles no ha rastreado y de cuáles tiene una versión vieja.
# Es lo que hace el inspector de URL de Bing Webmaster Tools, pero para todas de una vez.
#
# Uso:  .\aws\bing-check.ps1                     (todas las del sitemap)
#       .\aws\bing-check.ps1 /en /temas/azucar   (solo esas)
#       .\aws\bing-check.ps1 -Enviar             (además pide a Bing que lea las que no tiene al día; gasta cuota)
#
# La clave SÍ es un secreto (da acceso a toda la cuenta de Bing Webmaster): se genera en bing.com/webmasters,
# Ajustes > API access > API Key, y se guarda una vez en una variable de entorno de usuario:
#   [Environment]::SetEnvironmentVariable("BING_WEBMASTER_API_KEY", "<la clave>", "User")
# La API solo la admite en la dirección de la petición, así que este script nunca muestra direcciones ni errores
# en bruto: solo el código de respuesta.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Paths, [switch]$Enviar)
$ErrorActionPreference = "Stop"

$key = [Environment]::GetEnvironmentVariable("BING_WEBMASTER_API_KEY", "User")
if (-not $key) { $key = $env:BING_WEBMASTER_API_KEY }
if (-not $key) {
    Write-Host "Falta la variable de entorno BING_WEBMASTER_API_KEY (ver la cabecera de este script)."
    exit 1
}

$site = "https://peatying.com"
$siteUrl = "$site/"                       # tal cual está registrada la propiedad en Bing Webmaster Tools
$api = "https://ssl.bing.com/webmaster/api.svc/json"

# El sitemap del repo: cada dirección con su lastmod.
$sitemap = [xml](Get-Content "$PSScriptRoot\..\src\main\resources\static\sitemap.xml" -Raw -Encoding UTF8)
$lastmod = @{}
foreach ($u in $sitemap.urlset.url) { $lastmod[$u.loc] = $u.lastmod }
if ($Paths) { $urls = $Paths | ForEach-Object { $site + $_ } } else { $urls = $sitemap.urlset.url | ForEach-Object { $_.loc } }

# Una llamada a la API. Si falla, devuelve solo el código: el mensaje de error original lleva la clave dentro.
function Invoke-Bing([string]$method, [string]$query, $body) {
    $uri = "$api/$method" + "?apikey=$key" + $query
    try {
        if ($null -ne $body) {
            return Invoke-RestMethod -Uri $uri -Method Post -Body ($body | ConvertTo-Json) -ContentType "application/json; charset=utf-8"
        }
        return Invoke-RestMethod -Uri $uri -Method Get
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        throw "Bing respondio $code a $method"
    }
}

# Las fechas llegan como "/Date(1315349995266-0700)/": milisegundos desde 1970. Antes de 2000 = nunca.
function ConvertFrom-BingDate([string]$value) {
    if ($value -match '\((-?\d+)') {
        $date = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$Matches[1]).UtcDateTime
        if ($date.Year -ge 2000) { return $date }
    }
    return $null
}

$rows = @()
foreach ($url in $urls) {
    $query = "&siteUrl=" + [uri]::EscapeDataString($siteUrl) + "&url=" + [uri]::EscapeDataString($url)
    try {
        # Bing corta con un 400 ("ThrottleHost") si se le pregunta deprisa: se espera y se repite, hasta 4 veces.
        $info = $null
        for ($try = 1; -not $info; $try++) {
            try { $info = (Invoke-Bing "GetUrlInfo" $query $null).d }
            catch { if ($try -ge 4 -or "$_" -notmatch " 400 ") { throw }; Start-Sleep -Seconds (20 * $try) }
        }
        $crawled = ConvertFrom-BingDate $info.LastCrawledDate
        $found = ConvertFrom-BingDate $info.DiscoveryDate
        $changed = $lastmod[$url]
        if (-not $found -and -not $crawled) { $state = "sin rastrear" }   # la API no distingue "descubierta" de "desconocida"
        elseif (-not $crawled) { $state = "sin rastrear" }
        elseif ($changed -and $crawled.ToString("yyyy-MM-dd") -lt $changed) { $state = "version vieja" }
        else { $state = "al dia" }
        $rows += [pscustomobject]@{
            Estado = $state
            Pagina = $url.Substring($site.Length)
            Lastmod = $changed
            Rastreada = if ($crawled) { $crawled.ToString("yyyy-MM-dd") } else { "-" }
            Descubierta = if ($found) { $found.ToString("yyyy-MM-dd") } else { "-" }
            Http = $info.HttpStatus
            Bytes = $info.DocumentSize
        }
    } catch {
        $rows += [pscustomobject]@{ Estado = "error"; Pagina = $url.Substring($site.Length); Lastmod = $lastmod[$url]
            Rastreada = "-"; Descubierta = "-"; Http = "$_"; Bytes = "" }
    }
    Start-Sleep -Seconds 3            # mas deprisa, Bing empieza a rechazar peticiones (ThrottleHost)
}

$rows | Sort-Object Estado, Pagina | Format-Table -AutoSize | Out-Host
$rows | Group-Object Estado | Sort-Object Count -Descending | ForEach-Object { Write-Host ("{0,3}  {1}" -f $_.Count, $_.Name) }

if ($Enviar) {
    $pending = @($rows | Where-Object { $_.Estado -in "sin rastrear", "version vieja" } | ForEach-Object { $site + $_.Pagina })
    if ($pending.Count -eq 0) { Write-Host "Nada que enviar: Bing lo tiene todo al dia."; exit 0 }
    $quota = (Invoke-Bing "GetUrlSubmissionQuota" ("&siteUrl=" + [uri]::EscapeDataString($siteUrl)) $null).d
    Write-Host "Cuota de envios: $($quota.DailyQuota) hoy, $($quota.MonthlyQuota) este mes."
    $batch = @($pending | Select-Object -First ([Math]::Max(0, [int]$quota.DailyQuota)))
    if ($batch.Count -eq 0) { Write-Host "Sin cuota hoy: no se envia nada."; exit 0 }
    Invoke-Bing "SubmitUrlBatch" "" @{ siteUrl = $siteUrl; urlList = $batch } | Out-Null
    Write-Host "Enviadas $($batch.Count) de $($pending.Count) direcciones a Bing."
}
