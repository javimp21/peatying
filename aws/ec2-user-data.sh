#!/bin/bash
# Script que el servidor EC2 ejecuta UNA vez, al arrancar por primera vez ("user data").
# Prepara la máquina: instala Docker y Docker Compose y crea las carpetas de la app.
# El despliegue de la app en sí lo hace aws/deploy.sh, que se puede repetir cuantas veces haga falta.
set -euo pipefail   # parar al primer error, y no usar variables sin definir

dnf update -y
dnf install -y docker
systemctl enable --now docker            # arranca Docker ahora y cada vez que se encienda la máquina

# Docker Compose como plugin ("docker compose ..."). Versión fija, para que no cambie sin querer.
COMPOSE_VERSION=v5.5.1
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-aarch64" \
     -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Carpetas de la app. data/ la usa el contenedor (usuario sin privilegios) para los artículos descargados.
mkdir -p /opt/ragpeat/data
chmod 777 /opt/ragpeat/data

echo "user-data terminado" > /opt/ragpeat/READY
