# mqs-pg 前端镜像（L2）
#
# 多阶段：Node 构建静态产物 → nginx 托管。
# 构建上下文同样是仓库根目录（见 docker-compose.yml），方便统一排除 node_modules。

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build

# corepack 自带 pnpm，避免在镜像里 npm i -g 一个额外全局包。
# 版本要与 pnpm-workspace.yaml / lockfile 对得上，否则 --frozen-lockfile 会失败。
RUN corepack enable && corepack prepare pnpm@12 --activate

# 先只拷清单文件预热依赖，改业务代码时不必重装
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN pnpm build

# ---------- 运行阶段 ----------
FROM nginx:alpine

# 反代 /api 到后端服务。这里用服务名 backend（compose 里的服务名），
# **不是 localhost** —— 容器内的 localhost 是它自己。
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD ["wget", "-q", "--spider", "http://localhost/"]
