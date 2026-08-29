FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN GOPROXY=https://goproxy.cn,direct go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/matelink-adapter ./cmd/adapter

FROM alpine:3.21
RUN adduser -D -H -u 10001 matelink
USER matelink
COPY --from=build /out/matelink-adapter /usr/local/bin/matelink-adapter
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/matelink-adapter"]
