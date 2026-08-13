# 林曦日记 · 一体化镜像（多阶段构建）
# ① node 构建后台前端 dist  ② go 将 dist 拷进 server/webdist 后编译（内嵌）  ③ 精简运行时
# 构建上下文为仓库根（compose: build.context=.），故路径以 admin/ server/ 开头。

# ---- 阶段①：构建后台前端（Vue art-design-pro）dist ----
FROM node:22-alpine AS web
WORKDIR /admin
ENV npm_config_registry=https://registry.npmmirror.com
COPY admin/ ./
# 清掉可能被拷入的本地 node_modules/dist（避免跨平台二进制污染），再干净安装并构建
RUN rm -rf node_modules dist && npm install && npm run build

# ---- 阶段②：编译 Go 服务端（内嵌前端 dist） ----
FROM golang:1.22-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
# 用真实 admin dist 覆盖占位 webdist/（//go:embed all:webdist）
RUN rm -rf webdist
COPY --from=web /admin/dist/ ./webdist/
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o /out/linxi-server .

# ---- 阶段③：运行时（非 root） ----
FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/linxi-server /app/linxi-server
# config.yaml 与 uploads 通过挂载卷提供；静态后台前端已内嵌进二进制
USER app
EXPOSE 7740
ENTRYPOINT ["/app/linxi-server", "/app/config.yaml"]
