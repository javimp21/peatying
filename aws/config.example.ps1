# Datos de TU cuenta de AWS que usan los scripts de esta carpeta (deploy.ps1, stats.ps1...).
# Copia este archivo como aws\config.ps1 y rellénalo. config.ps1 no se sube a git (.gitignore):
# así el repositorio puede ser público sin mostrar el id del servidor.
$instanceId = "i-xxxxxxxxxxxxxxxxx"     # aws ec2 describe-instances --query "Reservations[].Instances[].InstanceId"
