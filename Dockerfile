# 构建阶段：使用 Maven + JDK 25 编译打包
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 运行阶段：仅 JRE
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/fee-rewrite-verify-1.0.0.jar app.jar
EXPOSE 8080
ENV DB_HOST=db DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASS=ccdev PORT=8080
ENTRYPOINT ["java", "-jar", "app.jar"]
