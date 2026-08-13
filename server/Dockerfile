# 林曦日记 服务端镜像（多阶段构建；内嵌 SQLite，纯 Go 无需 CGO）
FROM golang:1.22-alpine AS build
WORKDIR /src
ENV GOPROXY=https://goproxy.cn,direct
COPY . .
# 解析依赖（含 modernc.org/sqlite 及间接依赖），补全 go.sum
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w" -o /out/linxi-server .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 app
WORKDIR /app
COPY --from=build /out/linxi-server /app/linxi-server
# config.yaml、data(SQLite)、uploads 通过挂载卷提供
USER app
EXPOSE 7740
ENTRYPOINT ["/app/linxi-server", "/app/config.yaml"]
