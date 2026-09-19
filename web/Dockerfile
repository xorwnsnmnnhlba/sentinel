# syntax=docker/dockerfile:1

FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24-alpine
WORKDIR /app
# alpine에 tzdata가 없어 시스템 date는 UTC로 보이나, Node.js는 내장 ICU로 TZ 적용
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000 TZ=Asia/Seoul
RUN addgroup -S sentinel && adduser -S -G sentinel sentinel
COPY --from=build --chown=sentinel:sentinel /app/.next/standalone ./
COPY --from=build --chown=sentinel:sentinel /app/.next/static ./.next/static
COPY --from=build --chown=sentinel:sentinel /app/public ./public
USER sentinel
EXPOSE 3000
CMD ["node", "server.js"]
