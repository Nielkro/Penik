# Stage 1: Build client frontend
FROM node:20-alpine AS client-builder
WORKDIR /app/client
COPY client/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY client/ ./
RUN npm run build

# Stage 2: Build Go server with embedded frontend
FROM golang:1.26-alpine AS server-builder
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
FROM alpine:3.20
WORKDIR /app
RUN apk add --no-cache ca-certificates tzdata
RUN mkdir -p /app/data /app/data/upload /app/data/stickers
COPY --from=server-builder /app/bin/penik-server /app/penik-server

ENV PORT=8143
ENV DB_PATH=/app/data/messenger.db
ENV UPLOAD_DIR=/app/data/upload
ENV STICKERS_DIR=/app/data/stickers

EXPOSE 8143
VOLUME ["/app/data"]
ENTRYPOINT ["/app/penik-server"]
