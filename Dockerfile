FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/build/install/app /app
EXPOSE 8080
USER 1000
ENTRYPOINT ["/app/bin/app"]
