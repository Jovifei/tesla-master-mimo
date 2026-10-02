FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN GOPROXY=https://goproxy.cn,direct go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/matelink-archive-bridge .

FROM alpine:3.21
RUN adduser -D -H -u 10002 matelink-archive
USER matelink-archive
COPY --from=build /out/matelink-archive-bridge /usr/local/bin/matelink-archive-bridge
ENTRYPOINT ["/usr/local/bin/matelink-archive-bridge"]
