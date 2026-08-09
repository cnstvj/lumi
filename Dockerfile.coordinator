# Build stage
FROM rust:1.75-slim as builder
WORKDIR /app
COPY . .
RUN cargo build --release --bin coordinator

# Run stage
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /app/target/release/coordinator /app/coordinator
EXPOSE 4000
ENTRYPOINT ["/app/coordinator"]
