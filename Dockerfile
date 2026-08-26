# Stage 1: Build client frontend
FROM node:20-alpine AS client-builder
WORKDIR /app/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# Stage 2: Build Go server with embedded frontend
FROM golang:1.24-alpine AS server-builder
WORKDIR /app
RUN apk add --no-cache git
COPY server/go.mod server/go.sum ./server/
WORKDIR /app/server
RUN go mod download
COPY server/ ./
COPY --from=client-builder /app/client/dist /app/server/cmd/server/dist
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /app/bin/penik-server ./cmd/server

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
