# Axiom - 多阶段构建 / Multi-stage build
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
# 严格使用 npm ci：锁文件不存在时直接失败，而不是静默 npm install 生成
# 不可复现的依赖树。/ Fail hard when the lockfile is missing instead of silently
# falling back to npm install and producing an unreproducible dependency tree.
RUN if [ ! -f package-lock.json ]; then \
      echo "ERROR: frontend/package-lock.json is required for reproducible builds" && exit 1; \
    fi && npm ci --no-audit --no-fund

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

# 构建 jar（跳过测试）并归一化产物名：
# -Dspring-boot.repackage.skip=false 不需要，spring-boot repackage 默认开启；
# mvn 会产出 axiom-<version>.jar 与 axiom-<version>.jar.original 两个文件，
# 直接在构建阶段复制为固定名 /app.jar，运行时镜像不再与 pom 版本号双写——
# 升级 pom version 不需要同步修改 Dockerfile。/ Normalize the artifact name in the
# builder stage: mvn emits both axiom-<version>.jar and .jar.original, so we copy the
# packaged jar to a fixed /app.jar here. The runtime stage no longer hardcodes the
# version, so bumping the pom version never breaks the Docker build.
RUN cd backend && mvn -q clean package -DskipTests \
    && JAR=$(ls target/axiom-*.jar | grep -v '\.original$' | head -n 1) \
    && cp "$JAR" /build/app.jar

# ============ Stage 3: Runtime ============
FROM ${JRE_IMAGE} AS runtime
WORKDIR /app

# 安装 curl 与 CA 证书：curl 用于健康检查与模型下载；CA 证书保证 HTTPS 拉取可信
# Install curl and CA certs: curl for healthcheck and model download; CA certs for trusted HTTPS.
# eclipse-temurin:17-jre 基于 Debian，用 apt / Based on Debian, use apt.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 以非 root 运行：应用自身无鉴权，root 容器被逃逸后等于主机 root。
# / Run as a non-root user: the app is unauthenticated, so a container escape
# must not yield host root.
RUN groupadd -r axiom && useradd -r -g axiom axiom \
    && mkdir -p /app/data && chown -R axiom:axiom /app
USER axiom

# 复制可执行 jar（构建阶段已归一化为固定名，见 backend-builder / normalized in builder stage）
COPY --from=backend-builder /build/app.jar /app/app.jar

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
LABEL org.opencontainers.image.title="Axiom" \
      org.opencontainers.image.description="Axiom deterministic analytics engine with embedded DuckDB/H2/Zvec" \
      org.opencontainers.image.version="1.0.0"

# 环境变量：容器内路径 / Container paths
ENV DATA_DIR=/app/data \
    DUCKDB_PATH=/app/data/duckdb/analysis.duckdb \
    H2_URL='jdbc:h2:file:/app/data/h2/metadata' \
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
