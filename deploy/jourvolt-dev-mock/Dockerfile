FROM golang:1.22-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY}
RUN go mod download
COPY *.go ./
ARG BUILD_SHA=unknown
ARG BUILD_TIME=unknown
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.buildSHA=${BUILD_SHA} -X main.buildTime=${BUILD_TIME}" -o /out/jourvolt-api .

FROM alpine:3.20
RUN adduser -D -H -u 10001 jourvolt
USER jourvolt
COPY --from=build /out/jourvolt-api /jourvolt-api
EXPOSE 8080
ENTRYPOINT ["/jourvolt-api"]
