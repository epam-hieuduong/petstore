FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY host.json .
COPY src ./src
RUN mvn clean package

FROM mcr.microsoft.com/azure-functions/java:4-java21
ENV AzureWebJobsScriptRoot=/home/site/wwwroot
ENV AzureFunctionsJobHost__Logging__Console__IsEnabled=true
COPY --from=build /app/target/azure-functions/petstoreorderitemsreserver /home/site/wwwroot
EXPOSE 80
