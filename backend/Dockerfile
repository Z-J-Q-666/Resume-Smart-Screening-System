# 构建上下文是项目根目录（docker-compose 里指定），所以路径都带 backend/ 前缀

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 依赖层单独一层：pom.xml 没变就不重新下载，改代码只重跑最后一步
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY backend/pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY backend/src ./src
# spring-boot repackage 会留一个 .jar.original，必须排掉，否则 COPY 多文件到单文件会报错
RUN mvn -B -q -DskipTests package \
 && cp "$(find target -maxdepth 1 -name '*.jar' ! -name '*.original' | head -1)" app.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r app && useradd -r -g app app \
 && mkdir -p /data/uploads && chown -R app:app /data/uploads /app

COPY --from=build /build/app.jar /app/app.jar

# 上传目录由 compose 挂卷，这里只声明意图
ENV UPLOAD_DIR=/data/uploads
USER app
EXPOSE 8080

# MaxRAMPercentage 让 JVM 跟着容器内存走，不要写死 -Xmx
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
