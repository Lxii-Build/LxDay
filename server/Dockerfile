# 林曦日记 服务端镜像（多阶段构建；内嵌 SQLite，纯 Go 无需 CGO）
# HEIC/AVIF 解码器声明 go >= 1.25；分离部署镜像也必须与根 Dockerfile/CI 保持一致。
FROM golang:1.25-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -mod=readonly -trimpath -ldflags "-s -w" -o /out/linxi-server .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata su-exec && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/linxi-server /app/linxi-server
# config.yaml、data(SQLite)、uploads 与 uploads-private 通过挂载卷提供。
# 先建目录并赋权：命名卷首次挂载会沿用这些权限，非 root 进程才能真正启动并写库。
RUN mkdir -p /app/data /app/uploads /app/uploads-private \
    && chown -R app:app /app
COPY docker-entrypoint.sh /usr/local/bin/lxday-entrypoint
RUN chmod 0755 /usr/local/bin/lxday-entrypoint
EXPOSE 7740
ENTRYPOINT ["/usr/local/bin/lxday-entrypoint"]
CMD ["/app/linxi-server", "/app/config.yaml"]
