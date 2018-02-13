# Verified with http://hadolint.lukasmartinelli.ch/
FROM hbpmip/scala-base-build:1.1.0-0 as scala-build-env

# First caching layer: build.sbt and sbt configuration
COPY build.sbt /build/
RUN  mkdir -p /build/project/
COPY project/build.properties project/plugins.sbt project/.gitignore /build/project/

# Run sbt on an empty project and force it to download most of its dependencies to fill the cache
RUN sbt -mem 1500 compile

# Second caching layer: project sources
COPY src/ /build/src/
COPY .git/ /build/.git/
COPY .circleci/ /build/.circleci/
COPY tests/ /build/tests/
COPY docker/ /build/docker/
COPY docs/ /build/docs/
COPY .*.cfg .*ignore .*.yaml .*.conf .gitattributes *.md *.sh *.yml *.json *.txt Dockerfile LICENSE /build/

RUN /check-sources.sh

RUN sbt -mem 1500 test assembly

FROM hbpmip/java-base:8u151-1

MAINTAINER Ludovic Claude <ludovic.claude@chuv.ch>

ARG BUILD_DATE
ARG VCS_REF
ARG VERSION

RUN apk update \
    && apk add --no-cache java-cacerts

COPY docker/runner/woken.sh /opt/woken/
ADD  docker/lets-encrypt-install.sh /opt/woken/

RUN  chmod +x /opt/woken/lets-encrypt-install.sh
RUN  /opt/woken/lets-encrypt-install.sh

RUN adduser -H -D -u 1000 woken \
    && chmod +x /opt/woken/woken.sh \
    && ln -s /opt/woken/woken.sh /run.sh \
    && chown -R woken:woken /opt/woken \
    && apk add --update --no-cache curl

COPY --from=scala-build-env /build/target/scala-2.11/woken-all.jar /opt/woken/woken.jar

USER woken

# Health checks on http://host:8087/health
# Akka on 8088
EXPOSE 8087 8088 8088/UDP

HEALTHCHECK CMD curl -v --silent http://localhost:8087/health 2>&1 | grep UP

LABEL org.label-schema.build-date=$BUILD_DATE \
      org.label-schema.name="hbpmip/woken" \
      org.label-schema.description="An orchestration platform for Docker containers running data mining algorithms" \
      org.label-schema.url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-type="git" \
      org.label-schema.vcs-url="https://github.com/LREN-CHUV/woken" \
      org.label-schema.vcs-ref=$VCS_REF \
      org.label-schema.version="$VERSION" \
      org.label-schema.vendor="LREN CHUV" \
      org.label-schema.license="Apache 2.0" \
      org.label-schema.docker.dockerfile="Dockerfile" \
      org.label-schema.memory-hint="2048" \
      org.label-schema.schema-version="1.0"

# 8087: Web service API
# 8088: Akka cluster
EXPOSE 8087 8088

CMD ["/run.sh"]
