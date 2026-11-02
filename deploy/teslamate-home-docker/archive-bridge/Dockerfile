FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN GOPROXY=https://goproxy.cn,direct go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/matelink-archive-bridge .

FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata && adduser -D -H -u 10002 matelink-archive \
    && mkdir -p /run/matelink-battery-credentials && chown 10002:10002 /run/matelink-battery-credentials && chmod 0700 /run/matelink-battery-credentials
USER matelink-archive
COPY --from=build /out/matelink-archive-bridge /usr/local/bin/matelink-archive-bridge
ENTRYPOINT ["/usr/local/bin/matelink-archive-bridge"]
