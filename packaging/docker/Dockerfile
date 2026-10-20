# syntax=docker/dockerfile:1

FROM node:22-bookworm AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --shell /usr/sbin/nologin secs \
    && mkdir -p /app /data \
    && chown -R secs:secs /data
WORKDIR /app

COPY --from=build /src/secs-*/target/*.jar /app/lib/
COPY --from=web /src/frontend/dist /app/web
COPY packaging/linux/demo.json /app/demo.json
COPY packaging/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER secs
EXPOSE 8080 5000
VOLUME ["/data"]
ENTRYPOINT ["/app/entrypoint.sh"]
