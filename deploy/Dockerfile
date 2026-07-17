FROM eclipse-temurin:17-jre
WORKDIR /app
COPY backend/target/kb-service-0.1.0.jar app.jar
EXPOSE 9527
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
