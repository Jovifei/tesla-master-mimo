FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/battery-trend ./cmd/battery-trend
COPY internal/credentialsocket ./internal/credentialsocket
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/matelink-battery-trend ./cmd/battery-trend

FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata && adduser -D -H -u 10002 matelink-trend \
    && mkdir -p /run/matelink-battery-credentials /var/lib/matelink-battery-trend \
    && chown 10002:10002 /run/matelink-battery-credentials /var/lib/matelink-battery-trend \
    && chmod 0700 /run/matelink-battery-credentials /var/lib/matelink-battery-trend
USER matelink-trend
COPY --from=build /out/matelink-battery-trend /usr/local/bin/matelink-battery-trend
ENTRYPOINT ["/usr/local/bin/matelink-battery-trend"]
