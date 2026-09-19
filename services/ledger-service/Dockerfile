# Built AFTER the CI job's own `mvn install` step already produced
# target/*.jar — this Dockerfile only packages that already-tested jar,
# it never runs Maven itself (keeps the image build fast and avoids
# duplicating the build/test step CI already did).
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY target/*.jar app.jar

EXPOSE 8081

# Runtime config (DB_URL, DB_USERNAME, MAIL_USERNAME, etc.) is supplied as
# environment variables by whatever runs this container (Dokploy) — never
# baked into the image itself, matching the ${VAR:default} pattern every
# service's own application.yml already uses.
ENTRYPOINT ["java", "-jar", "app.jar"]
