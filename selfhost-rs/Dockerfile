# Multi-stage build for the distributed Rust control plane.
FROM rust:1-slim AS build
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/release/openuuyc-selfhost /usr/local/bin/openuuyc-selfhost
RUN useradd -r -u 10001 openuuyc && mkdir -p /data && chown openuuyc /data
USER openuuyc
EXPOSE 8844
ENTRYPOINT ["/usr/local/bin/openuuyc-selfhost"]
