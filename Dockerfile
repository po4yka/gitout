# Build the application using a recent Rust release.
FROM rust:1.87.0 AS rust
RUN rustup component add clippy rustfmt
WORKDIR /app
COPY Cargo.toml Cargo.lock .rustfmt.toml ./
COPY src ./src
RUN cargo build --release
RUN cargo clippy
RUN cargo test
RUN cargo fmt -- --check


FROM golang:1.24-alpine AS shell
RUN apk add --no-cache shellcheck
ENV GO111MODULE=on
RUN go install mvdan.cc/sh/v3/cmd/shfmt@latest
WORKDIR /overlay
COPY root/ ./
COPY .editorconfig /
RUN find ./etc -type f -exec chmod +x {} +
RUN find ./etc -type f -exec shellcheck -s sh {} +
RUN find ./etc -type f -exec shfmt -w {} +


FROM debian:bookworm-slim
ARG TARGETARCH
RUN apt-get update && \
    apt-get install -y --no-install-recommends bash ca-certificates curl xz-utils && \
    rm -rf /var/lib/apt/lists/*
ARG S6_OVERLAY_VERSION=3.2.1.0
ADD https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-noarch.tar.xz /tmp/
RUN tar -C / -Jxpf /tmp/s6-overlay-noarch.tar.xz
# Pick the right arch‑specific overlay (x86_64 or aarch64)
RUN set -eux; \
    case "${TARGETARCH}" in \
      "amd64")  S6_ARCH="x86_64"  ;; \
      "arm64")  S6_ARCH="aarch64" ;; \
      *) echo "Unsupported TARGETARCH=${TARGETARCH}" && exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/s6-overlay-${S6_ARCH}.tar.xz \
      "https://github.com/just-containers/s6-overlay/releases/download/v${S6_OVERLAY_VERSION}/s6-overlay-${S6_ARCH}.tar.xz" && \
    tar -C / -Jxpf /tmp/s6-overlay-${S6_ARCH}.tar.xz
ENTRYPOINT ["/init"]
ENV \
    # Fail if cont-init scripts exit with non-zero code.
    S6_BEHAVIOUR_IF_STAGE2_FAILS=2 \
    # Show full backtraces for crashes.
    RUST_BACKTRACE=full \
    CRON="" \
    HEALTHCHECK_ID="" \
    HEALTHCHECK_HOST="https://hc-ping.com" \
    PUID="" \
    PGID="" \
    GITOUT_ARGS=""
# ensure all s6 init scripts are world‑executable
COPY --from=shell /overlay/ /
WORKDIR /app
COPY --from=rust /app/target/release/gitout ./
