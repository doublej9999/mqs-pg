# mqs-pg 后端镜像（L2）
#
# 多阶段：用 Maven 镜像构建，只把 jar 搬进 JRE 镜像。这样运行镜像里
# 没有 Maven、没有源码、没有 ~/.m2，体积和攻击面都小很多。
#
# 注意：构建上下文是**仓库根目录**（见 docker-compose.yml 的 build.context），
# 不是 backend/ —— 因为 .dockerignore 在根目录，便于统一排除 frontend/node_modules。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 并预热依赖：改一行 Java 代码时不必重新下载整个依赖树。
# 这一步顺序很关键，反过来（先拷源码再 go-offline）每次构建都会重下依赖。
COPY backend/pom.xml .
RUN mvn -B --no-transfer-progress dependency:go-offline -DskipTests || true

COPY backend/src ./src
RUN mvn -B --no-transfer-progress package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre

# 非 root 运行。容器逃逸时这一步能显著抬高攻击成本，
# 而且不少集群的安全策略直接禁止 root 容器。
RUN groupadd --system --gid 1001 mqspg \
 && useradd --system --uid 1001 --gid mqspg --create-home mqspg \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/mqs-pg-backend-*.jar app.jar
RUN chown -R mqspg:mqspg /app
USER mqspg

# JAVA_OPTS 用于注入 -Xmx 等；exec 形式会被覆盖，所以用 sh -c 保留变量展开。
# 默认值给容器内存的 75%，不写死堆大小 —— 写死会让小规格 Pod 直接 OOMKilled。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

EXPOSE 8080

# 健康检查打 actuator 的 liveness 组（不是 readiness）：
# readiness 失败只该摘流量，容器本身还好着，重启它没有意义。
#
# 用 curl：运行阶段是 Ubuntu 基础镜像，curl 未必预装，已在上面的 RUN 里显式
# apt 装一次 —— 这样升级基础镜像也不会因为「恰好没有 curl」而静默失效。
# （曾经想用 /dev/tcp，那是 **bash** 特性，而 HEALTHCHECK 走的是 sh(dash)，不成立。）
#
# 用 127.0.0.1 而不是 localhost：/etc/hosts 会把 localhost 同时映射到 ::1 与
# 127.0.0.1，解析顺序不保证；而 Spring Boot 默认只绑 IPv4 的 0.0.0.0。
# 写 IPv4 字面量可绕开这个不确定性（前端镜像里实测踩到过同类问题）。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:8080/actuator/health/liveness"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
