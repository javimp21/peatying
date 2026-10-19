# Arranca la web en local: http://localhost:8080
# Lee las claves de tus variables de entorno de usuario (las que guardaste con SetEnvironmentVariable),
# por si esta terminal se abrió antes de guardarlas. Nunca las muestra.
#
# Uso:  .\run-local.ps1          (antes: docker compose up -d  y  .\mvnw.cmd package -DskipTests)

$env:VOYAGE_API_KEY    = [Environment]::GetEnvironmentVariable("VOYAGE_API_KEY", "User")
$env:ANTHROPIC_API_KEY = [Environment]::GetEnvironmentVariable("ANTHROPIC_API_KEY", "User")

java -jar "$PSScriptRoot\target\rag-peat-0.0.1-SNAPSHOT.jar"
