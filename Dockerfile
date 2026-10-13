FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -Dmaven.test.skip=true package

# The opt-in test target runs the standard Maven suite during its build.
FROM build AS test
RUN mvn -B -ntp test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd --gid 10001 delta \
    && useradd --uid 10001 --gid delta --no-create-home --shell /usr/sbin/nologin delta
COPY --from=build --chown=delta:delta /build/target/delta_api_mongo-*.jar /app/app.jar

USER delta
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
