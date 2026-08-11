# 林曦日记 后台前端镜像（构建静态资源 + Nginx 托管）
FROM node:22-alpine AS build
WORKDIR /app
ENV npm_config_registry=https://registry.npmmirror.com
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# 反代与静态托管配置由 deploy/nginx.conf 挂载到 /etc/nginx/conf.d/default.conf
EXPOSE 80
