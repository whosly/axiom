# 数据分析 Agent - 多阶段构建 / Multi-stage build
# Stage 1: Node 构建前端 / Build frontend
# Stage 2: Maven 构建后端 / Build backend
# Stage 3: 运行时镜像 / Runtime image

# 构建参数：基础镜像可替换 / Build args: overridable base images
ARG NODE_IMAGE=node:20
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre

# ============ Stage 1: Frontend build ============
FROM ${NODE_IMAGE} AS frontend-builder
WORKDIR /build/frontend

# 先复制依赖清单，利用 Docker 缓存 / Copy manifests first for layer caching
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# ============ Stage 2: Backend build ============
FROM ${MAVEN_IMAGE} AS backend-builder
WORKDIR /build

# 可选 Maven 镜像：弱网环境（如国内）可设置 MAVEN_MIRROR 加速依赖下载
# Optional Maven mirror: set MAVEN_MIRROR to speed up dependency downloads on slow networks.
# 留空则使用 Maven 默认中央仓库 / Empty means the default Maven Central.
ARG MAVEN_MIRROR=
RUN if [ -n "${MAVEN_MIRROR}" ]; then \
      mkdir -p /root/.m2 && \
      printf '<settings><mirrors><mirror><id>custom</id><name>custom</name><url>%s</url><mirrorOf>*</mirrorOf></mirror></mirrors></settings>' \
        "${MAVEN_MIRROR}" > /root/.m2/settings.xml; \
    fi

# pom 独立成层：仅 pom 变动时使后续构建层失效，源码变动不重复复制清单
# Keep pom in its own layer: only pom changes invalidate downstream layers.
COPY backend/pom.xml ./backend/pom.xml

# 复制后端源码 + 前端构建产物 / Copy backend sources + built frontend
COPY backend/src ./backend/src
COPY --from=frontend-builder /build/frontend/dist ./backend/src/main/resources/static

# 构建 jar（跳过测试）/ Build executable jar
RUN cd backend && mvn -q clean package -DskipTests

# ============ Stage 3: Runtime ============
FROM ${JRE_IMAGE} AS runtime
WORKDIR /app

# 安装 curl 与 CA 证书：curl 用于健康检查与模型下载；CA 证书保证 HTTPS 拉取可信
# Install curl and CA certs: curl for healthcheck and model download; CA certs for trusted HTTPS.
# eclipse-temurin:17-jre 基于 Debian，用 apt / Based on Debian, use apt.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 复制可执行 jar / Copy executable jar
COPY --from=backend-builder /build/backend/target/data-analysis-agent-1.0.0.jar /app/app.jar

# 创建数据目录结构 / Create data directories
RUN mkdir -p /app/data/h2 /app/data/duckdb /app/data/sqlite /app/data/zvec /app/data/models/bge-small-zh-v1.5

# 下载本地 embedding 模型（bge-small-zh-v1.5 ONNX）
# 使用 hf-mirror 镜像，构建期下载一次；--retry 提升弱网构建成功率
# Download embedding model at build time via mirror; --retry improves reliability on weak networks.
ARG MODEL_MIRROR=https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main
RUN curl -fsSL --retry 3 --retry-delay 3 -o /app/data/models/bge-small-zh-v1.5/vocab.txt \
      "${MODEL_MIRROR}/vocab.txt" && \
    curl -fsSL --retry 3 --retry-delay 3 -o /app/data/models/bge-small-zh-v1.5/model.onnx \
      "${MODEL_MIRROR}/onnx/model.onnx"

# 优雅停机：容器停止时给 Spring Boot `shutdown: graceful` 留出收尾时间
# Graceful stop: give Spring Boot `shutdown: graceful` time to finish in-flight requests.
STOPSIGNAL SIGTERM

# OCI 镜像元数据 / OCI image metadata
LABEL org.opencontainers.image.title="data-analysis-agent" \
      org.opencontainers.image.description="AI data analysis agent with embedded DuckDB/H2/Zvec" \
      org.opencontainers.image.version="1.0.0"

# 环境变量：容器内路径 / Container paths
ENV DATA_DIR=/app/data \
    DUCKDB_PATH=/app/data/duckdb/analysis.duckdb \
    H2_URL='jdbc:h2:file:/app/data/h2/metadata;AUTO_SERVER=TRUE' \
    SQLITE_URL='jdbc:sqlite:/app/data/sqlite/metadata.db' \
    ZVEC_PATH=/app/data/zvec/dataset_semantics \
    ZVEC_KNOWLEDGE_PATH=/app/data/zvec/knowledge_base_v2 \
    EMBEDDING_PROVIDER=local \
    EMBEDDING_MODEL_PATH=/app/data/models/bge-small-zh-v1.5/model.onnx \
    EMBEDDING_VOCAB_PATH=/app/data/models/bge-small-zh-v1.5/vocab.txt \
    ZVEC_DIMENSION=512 \
    JAVA_OPTS="-Xmx1g"

EXPOSE 8080

# 数据卷：持久化数据库、向量、模型 / Volume for DB, vectors and model
VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
