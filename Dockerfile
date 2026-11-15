# syntax=docker/dockerfile:1
# Repo Interviewer images (task 3.1, docs/LLD.md §14.1), one per service since task 4.1:
#   docker build -t graphql-gateway:local .                                             (the gateway, the default)
#   docker build --build-arg SERVICE=analysis-service -t analysis-service:local .
#   docker build --build-arg SERVICE=interview-service -t interview-service:local .

# --- Build stage: package the app with the Maven wrapper, then split the jar into layers ---------------------------
FROM eclipse-temurin:21-jdk-noble AS build
# The Maven module to build, which is also its folder and its jar's name.
ARG SERVICE=graphql-gateway
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
# Each module's pom.xml and src/main only (.dockerignore). Maven reads every module the parent pom lists, so all of
# them are here; only SERVICE and the libraries it needs are built.
COPY libs/ libs/
COPY analysis-service/ analysis-service/
COPY interview-service/ interview-service/
COPY graphql-gateway/ graphql-gateway/
# Tests are not run or compiled here: ./mvnw verify and CI run them (Testcontainers needs a Docker daemon, which a
# build step does not have). The cache mount keeps ~/.m2 between builds, so dependencies are downloaded only once.
# -pl SERVICE -am: that service and the libraries it needs ("also make"), each library a plain jar in the fat jar.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -Dmaven.test.skip=true -pl ${SERVICE} -am \
 && cp ${SERVICE}/target/${SERVICE}-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Runtime stage: a JRE only (no JDK, no Maven, no sources), not root -------------------------------------------
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
# The app runs as this user, never as root: someone who breaks into the app gets an account that cannot install
# packages, change system files or even its own jars (they stay root-owned and read-only). No login shell, no home.
# A fixed number, because Kubernetes can only check `runAsNonRoot` against a numeric user (task 3.3).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
# One image layer per jar layer, least-changing first: a code change rebuilds only the small last ones
# (application.jar, and snapshot-dependencies, which holds this project's own libraries), not the 158 MB of
# dependencies.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# The JVM sizes itself from the container's memory limit (cgroups): heap at most 75% of it, the rest for metaspace,
# thread stacks and network buffers. On OutOfMemoryError it exits, so Docker or Kubernetes restarts it. An env var,
# so a deployment can change it without a new image.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# application.jar's manifest puts lib/*.jar on the classpath. Profiles, URLs and secrets come from the environment.
ENTRYPOINT ["java", "-jar", "application.jar"]
