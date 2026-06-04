# syntax=docker/dockerfile:1.7
# ---- build stage ----
# Azul Zulu JDK 25 (multi-arch: amd64, arm64).
FROM azul/zulu-openjdk:25-jdk AS build
WORKDIR /src

RUN apt-get update \
 && apt-get install -y --no-install-recommends maven \
 && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime stage ----
# Azul Zulu JRE 25 headless. Raspberry Pi OS 64-bit works out of the box.
FROM azul/zulu-openjdk:25-jre-headless

# BlueZ client libs are not required (we talk over D-Bus), but `dbus` is
# useful for diagnostics inside the container.
RUN apt-get update \
 && apt-get install -y --no-install-recommends dbus libcap2-bin \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/quarkus-app/ /app/

EXPOSE 8090
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
