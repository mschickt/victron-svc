# syntax=docker/dockerfile:1.7
# ---- build stage ----
# Azul Zulu JDK 25 (multi-arch: amd64, arm64).
FROM azul/zulu-openjdk:25 AS build
WORKDIR /src

ARG MAVEN_VERSION=3.9.9
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xz -C /opt \
 && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

# Short-lived build JVMs don't benefit from aggressive JIT — cap it at tier 1
# and use the parallel GC. Noticeably faster Maven startup on a Raspberry Pi.
ENV MAVEN_OPTS="-XX:TieredStopAtLevel=1 -XX:+UseParallelGC -Dmaven.artifact.threads=8"

# 1) Resolve dependencies in their own layer — cached and re-run ONLY when
#    pom.xml changes, so source edits skip dependency resolution entirely.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -T 1C -DskipTests dependency:go-offline

# 2) Build. Source-only changes start here. -Dmaven.test.skip=true skips
#    compiling the tests too; -T 1C builds with all CPU cores.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q -T 1C -Dmaven.test.skip=true package

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
