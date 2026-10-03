# mts-ib-bridge on JDK 25.   docker build -t mts-ib-bridge .
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
ENV MAVEN_OPTS="-Xmx512m"
COPY pom.xml .
COPY third_party/ib-tws-api/pom.xml third_party/ib-tws-api/
COPY bridge/pom.xml bridge/
COPY third_party/ib-tws-api/src third_party/ib-tws-api/src
COPY bridge/src bridge/src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S bridge && adduser -S bridge -G bridge
WORKDIR /app
COPY --from=build /src/bridge/target/mts-ib-bridge.jar app.jar
COPY LICENSE third_party/ib-tws-api/NOTICE ./
USER bridge
# Defaults: paper Gateway on localhost, API on localhost, orders refused.
ENV IB_HOST=127.0.0.1 IB_PORT=4002 IB_CLIENT_ID=11 \
    BRIDGE_BIND=127.0.0.1 BRIDGE_PORT=8090 BRIDGE_ORDERS_ENABLED=false
EXPOSE 8090
HEALTHCHECK --interval=60s --timeout=5s --start-period=30s \
  CMD wget -qO- "http://127.0.0.1:${BRIDGE_PORT}/v1/status" >/dev/null || exit 1
ENTRYPOINT ["java", "-Xmx128m", "-XX:+UseSerialGC", "-jar", "app.jar"]
