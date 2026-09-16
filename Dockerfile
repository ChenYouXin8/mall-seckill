# =============================================================================
# mall-seckill 应用镜像（Spring Boot 3.2.5 / Java 17）
# 多阶段构建：Maven 3.9 + JDK21 编译（release=17）→ JRE17 运行
# =============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/mall-seckill-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
