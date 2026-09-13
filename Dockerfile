# Stage 1: Build client frontend
FROM node:22-alpine AS client-builder
WORKDIR /app/client

RUN apk add --no-cache bash curl unzip jq

ARG GITHUB_REPO="Nielkro/Penik"
ARG GITHUB_TOKEN=""

COPY client/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY client/ ./
COPY scripts/fetch_crypto.sh /tmp/fetch_crypto.sh
RUN chmod +x /tmp/fetch_crypto.sh && \
    GITHUB_REPO="${GITHUB_REPO}" GITHUB_TOKEN="${GITHUB_TOKEN}" \
    /tmp/fetch_crypto.sh --force --wasm /app/client/pkg/penik-crypto-wasm

RUN npm run build

# Stage 2: Build Go server with embedded frontend
FROM golang:1.27-alpine AS server-builder
WORKDIR /app
RUN apk add --no-cache git gcc musl-dev
COPY server/go.mod ./server/
COPY server/go.su[m] ./server/
WORKDIR /app/server
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY server/ ./
COPY --from=client-builder /app/client/dist ./cmd/server/dist
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 GOOS=linux go build -ldflags="-s -w" -o /app/bin/penik-server ./cmd/server

# Stage 3: Production runtime image
FROM alpine:3.24
WORKDIR /app
RUN apk add --no-cache ca-certificates tzdata ffmpeg
RUN mkdir -p /app/data /app/data/upload /app/data/stickers
COPY --from=server-builder /app/bin/penik-server /app/penik-server

ENV PORT=8143
ENV DB_PATH=/app/data/messenger.db
ENV UPLOAD_DIR=/app/data/upload
ENV STICKERS_DIR=/app/data/stickers

EXPOSE 8143
VOLUME ["/app/data"]

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8143/api/v1/health || exit 1

ENTRYPOINT ["/app/penik-server"]
