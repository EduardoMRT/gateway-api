FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/gateway-api-*.jar app.jar
# Config externa (hosts, apis, credenciais) via volume ou variaveis:
#   -v /etc/gateway/application.yml:/app/config/application.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
