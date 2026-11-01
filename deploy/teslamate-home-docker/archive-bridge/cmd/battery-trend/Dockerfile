FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd/battery-trend ./cmd/battery-trend
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/matelink-battery-trend ./cmd/battery-trend

FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata && adduser -D -H -u 10002 matelink-trend
USER matelink-trend
COPY --from=build /out/matelink-battery-trend /usr/local/bin/matelink-battery-trend
ENTRYPOINT ["/usr/local/bin/matelink-battery-trend"]
