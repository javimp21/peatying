#!/bin/bash
# Despliega (o actualiza) la app en el servidor. Se puede ejecutar las veces que haga falta.
# Uso:  deploy.sh <endpoint-de-RDS> [etiqueta-de-la-imagen]
set -euo pipefail

DB_HOST="$1"
APP_TAG="${2:-latest}"
REGION=eu-west-1
REGISTRY=984546305267.dkr.ecr.eu-west-1.amazonaws.com
cd /opt/ragpeat

# Lee un parámetro cifrado de Parameter Store. Puede hacerlo gracias al ROL del servidor:
# aquí no hay ninguna clave escrita, el servidor se identifica ante AWS por ser quien es.
param() {
  aws ssm get-parameter --region "$REGION" --name "$1" --with-decryption --query Parameter.Value --output text
}

# .env con los secretos: solo legible por root (chmod 600) y nunca sale del servidor.
umask 077
cat > .env <<EOF
DB_URL=jdbc:postgresql://${DB_HOST}:5432/ragpeat
DB_PASSWORD=$(param /ragpeat/db-password)
ANTHROPIC_API_KEY=$(param /ragpeat/anthropic-api-key)
VOYAGE_API_KEY=$(param /ragpeat/voyage-api-key)
ADMIN_TOKEN=$(param /ragpeat/admin-token 2>/dev/null || true)
APP_TAG=${APP_TAG}
EOF
# APP_TAG también en .env: así cualquier "docker compose run ..." posterior (stats, index...) usa
# la versión desplegada y no la imagen "latest", que es antigua.

# Base de datos DB-IP Lite (país y ciudad de una IP; CC BY 4.0). Sale una nueva cada mes: la bajamos
# si no está o si tiene más de 35 días. Si falla la descarga, seguimos con la que hubiera (o sin ninguna).
GEO=data/geo/dbip-city-lite.mmdb
if [ ! -f "$GEO" ] || [ -n "$(find "$GEO" -mtime +35)" ]; then
  mkdir -p data/geo
  if curl -sfL "https://download.db-ip.com/free/dbip-city-lite-$(date -u +%Y-%m).mmdb.gz" | gunzip > "$GEO.new"; then
    mv "$GEO.new" "$GEO"
  else
    rm -f "$GEO.new"
    echo "Aviso: no se pudo descargar DB-IP Lite"
  fi
fi
# umask 077 la dejaría solo para root: la app corre como otro usuario y tiene que poder leerla.
chmod 755 data/geo 2>/dev/null || true
chmod 644 "$GEO" 2>/dev/null || true

# Iniciar sesión en ECR (también con el rol) y arrancar la versión pedida.
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
export APP_TAG
docker compose pull
docker compose up -d --remove-orphans
# Si el Caddyfile cambió, Caddy no se entera solo: se lo recargamos (sin cortar las conexiones abiertas).
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
docker image prune -f      # borra imágenes viejas que ya no se usan, para no llenar el disco

docker compose ps
