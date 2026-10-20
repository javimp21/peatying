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
EOF

# Iniciar sesión en ECR (también con el rol) y arrancar la versión pedida.
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"
export APP_TAG
docker compose pull
docker compose up -d --remove-orphans
# Si el Caddyfile cambió, Caddy no se entera solo: se lo recargamos (sin cortar las conexiones abiertas).
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
docker image prune -f      # borra imágenes viejas que ya no se usan, para no llenar el disco

docker compose ps
