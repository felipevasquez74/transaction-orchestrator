FROM eclipse-temurin:17-jdk-alpine AS dependencies
WORKDIR /app

COPY pom.xml .

RUN apk add --no-cache maven=3.9.6-r0 2>/dev/null || apk add --no-cache maven && \
    mvn dependency:go-offline -q --no-transfer-progress

FROM dependencies AS builder
WORKDIR /app
COPY src ./src
RUN mvn package -DskipTests -q --no-transfer-progress

FROM eclipse-temurin:17-jre-alpine AS runtime

LABEL org.opencontainers.image.title="TumiPay Transaction Orchestrator"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.vendor="TumiPay"
LABEL org.opencontainers.image.description="Payment Transaction Orchestration Microservice"
LABEL maintainer="engineering@tumipay.co"

RUN addgroup -g 1001 -S tumipay && \
    adduser -u 1001 -S tumipay -G tumipay -H -s /sbin/nologin

RUN mkdir -p /app /tmp/heapdumps && \
    chown -R tumipay:tumipay /app /tmp/heapdumps

WORKDIR /app

COPY --from=builder --chown=tumipay:tumipay \
    /app/target/transaction-orchestrator-1.0.0.jar app.jar

USER tumipay

EXPOSE 8080

ENV JAVA_OPTS="\
  -server \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:MinRAMPercentage=25.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:+ExplicitGCInvokesConcurrent \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heapdumps/heapdump.hprof \
  -Xss512k \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC \
  -Dspring.output.ansi.enabled=NEVER"

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
