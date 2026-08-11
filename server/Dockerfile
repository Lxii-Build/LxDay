# 林曦日记 服务端镜像（多阶段构建）
FROM golang:1.22-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o /out/linxi-server .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/linxi-server /app/linxi-server
# config.yaml 与 uploads 通过挂载卷提供
USER app
EXPOSE 8080
ENTRYPOINT ["/app/linxi-server", "/app/config.yaml"]
