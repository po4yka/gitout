FROM alpine:3.23.2 AS build
ENV GRADLE_OPTS="-Xmx3g -XX:MaxMetaspaceSize=512m -Dkotlin.incremental=false -Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.logging.stacktrace=full"

RUN apk add --no-cache \
      openjdk21 \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk

WORKDIR /app

# Get the Gradle wrapper and cache the Gradle distribution first.
COPY gradlew settings.gradle ./
COPY gradle/wrapper ./gradle/wrapper
RUN ./gradlew --version

COPY gradle/libs.versions.toml ./gradle/libs.versions.toml
COPY build.gradle ./
COPY src/main ./src/main
RUN ./gradlew installDist

FROM alpine:3.23.2

VOLUME /config
VOLUME /data

RUN apk add --no-cache \
      git \
      openjdk17-jre-headless \
      tini \
      ca-certificates \
      bash \
      su-exec \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk \
 && update-ca-certificates

# Environment variables for configuration
# Note: SSL_CERT_FILE is NOT set here - Java uses its own cacerts, setting it breaks Java's TrustManager
# Note: GITOUT_DRY_RUN is intentionally not set (Clikt flag requires true/false or unset)
ENV GITOUT_TIMEOUT="10m" \
    GITOUT_HC_HOST="https://hc-ping.com"

WORKDIR /app
COPY --from=build /app/build/install/gitout ./

# Add entrypoint script for user management
COPY docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
CMD ["/config/config.toml", "/data"]
