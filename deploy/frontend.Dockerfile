FROM node:22-alpine AS build
ARG VITE_ORCH_BASE=""
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# 留空时前端走 PROD 默认:同源相对路径 /orchestrator/,由 nginx 反代到编排层。
# 只在编排层被部署到别的域名时才需要显式传这个 ARG(compose 里用 VITE_ORCH_BASE 覆盖)。
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
