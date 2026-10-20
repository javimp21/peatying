# Imagen Docker de la app, en dos etapas ("multi-stage build"):
#   1. "build": una imagen con el JDK completo y Maven, que compila el .jar.
#   2. La imagen final: solo el JRE (lo justo para EJECUTAR Java) y el .jar.
# Así la imagen final no lleva el compilador ni el código fuente: es más pequeña y más segura.

# --- Etapa 1: compilar ---------------------------------------------------------
# --platform=$BUILDPLATFORM: esta etapa se ejecuta con el procesador del ordenador que construye
# (rápido, sin emular). Da igual que el servidor sea ARM: un .jar de Java funciona en cualquier procesador.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Primero solo lo necesario para descargar dependencias. Docker guarda cada paso en caché:
# si el pom.xml no cambia, la próxima vez se salta la descarga (que es lo lento).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

# Luego el código, y compilamos (los tests ya los pasamos en local; aquí los saltamos).
COPY src/ src/
RUN ./mvnw -q -B package -DskipTests

# --- Etapa 2: la imagen que se ejecuta ------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# No ejecutamos como root: si alguien lograra colarse en la app, tendría menos permisos.
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /app/target/rag-peat-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
# MaxRAMPercentage: Java usa como máximo el 75 % de la memoria del contenedor, y deja el resto
# al sistema. Sin esto, en un servidor pequeño la JVM podría pedir más memoria de la que hay.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
