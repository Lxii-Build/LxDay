# 林曦日记 · 一体化镜像（多阶段构建）
# ① node 构建后台前端 dist  ② go 将 dist 拷进 server/webdist 后编译（内嵌）  ③ 精简运行时
# 构建上下文为仓库根（compose: build.context=.），故路径以 admin/ server/ 开头。

# ---- 阶段①：构建后台前端（Vue art-design-pro）dist ----
FROM node:22-alpine AS web
WORKDIR /admin
ENV npm_config_registry=https://registry.npmmirror.com
COPY admin/package.json admin/package-lock.json ./
# lockfile 是唯一依赖真源；npm install 会在构建时重新解析 ^ 版本，导致同一提交
# 在不同日期构出不同前端。先只拷依赖清单，缓存也能跨源码改动复用。
RUN npm ci
COPY admin/ ./
RUN npm run build

# ---- 阶段②：编译 Go 服务端（内嵌前端 dist） ----
# Go 1.25：HEIC/AVIF 解码器（gen2brain/heic|avif，底层 wazero）要求 go >= 1.25。
# 用 1.22 会在 `go mod tidy` 阶段直接失败（0821 的 CI 就是这么红的）。
# 它们是**纯 Go wasm 实现、无需 CGO**，所以下面仍能 CGO_ENABLED=0 静态编译。
FROM golang:1.25-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
# 用真实 admin dist 覆盖占位 webdist/（//go:embed all:webdist）
RUN rm -rf webdist
COPY --from=web /admin/dist/ ./webdist/
# modernc.org/sqlite 为纯 Go 实现，可在 CGO_ENABLED=0 下静态编译
RUN CGO_ENABLED=0 GOOS=linux go build -mod=readonly -trimpath -ldflags "-s -w" -o /out/linxi-server .

# ---- 阶段③：运行时（非 root；命名卷首次挂载会继承下方目录权限） ----
FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata wget su-exec \
    && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/linxi-server /app/linxi-server
# SQLite 数据目录、公开资源与私密相册目录（对应 compose 的三个卷挂载点）；
# 先建目录并赋权，避免服务端以 root 运行。使用命名卷时首次挂载会沿用这些权限。
RUN mkdir -p /app/data /app/uploads /app/uploads-private \
    && chown -R app:app /app
COPY docker-entrypoint.sh /usr/local/bin/lxday-entrypoint
RUN chmod 0755 /usr/local/bin/lxday-entrypoint
# 启动时先由 root 修复历史命名卷的属主，再降权运行服务。
# 仅在卷第一次被本镜像接管时递归修复，避免每次重启都扫描整套相册文件。
# config.yaml 可选（不打包进镜像，默认走环境变量 + 默认值）；后台前端已内嵌进二进制
EXPOSE 7740
ENTRYPOINT ["/usr/local/bin/lxday-entrypoint"]
CMD ["/app/linxi-server", "/app/config.yaml"]
