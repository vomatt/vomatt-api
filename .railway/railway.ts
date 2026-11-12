import { defineRailway, github, postgres, preserve, project, redis, service } from "railway/iac";

export const partial = "vomatt-api";

export default defineRailway(() => {
  const db = postgres("Postgres");
  const cache = redis("Redis");

  const api = service("vomatt-api", {
    source: github("vomatt/vomatt-api", { branch: "main" }),
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile.vercel" },
    healthcheck: "/actuator/health",
    healthcheckTimeout: 300,
    env: {
      // The app appends its own ?query, so DB_URL must be a bare JDBC URL
      DB_URL: "jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}",
      DB_USER: db.env.PGUSER,
      DB_PASSWORD: db.env.PGPASSWORD,
      REDIS_HOST: cache.env.REDISHOST,
      REDIS_PORT: cache.env.REDISPORT,
      REDIS_PASSWORD: cache.env.REDISPASSWORD,
      PROFILES_ACTIVE: "dev",
      LOG_LEVEL: "INFO",
      SWAGGER_ENABLED: "true",
      // RAILWAY_PUBLIC_DOMAIN is injected once a domain is generated
      OPENAPI_DEV_URL: "https://${{RAILWAY_PUBLIC_DOMAIN}}",
      OPENAPI_PROD_URL: "https://${{RAILWAY_PUBLIC_DOMAIN}}",
      JWT_EXPIRATION_SECONDS: "3600",
      JWT_REFRESH_EXPIRATION_DAYS: "30",
      EMAIL_ENABLED: "true",
      EMAIL_FROM: "Vomatt <noreply@vomatt.com>",
      EMAIL_SITE_URL: "https://vomatt.com",
      // Railway Hobby blocks outbound SMTP, so mail goes through the Resend HTTP API
      EMAIL_PROVIDER: "resend",
      MAIL_HOST: "smtp.gmail.com",
      MAIL_PORT: "587",
      // Keep /actuator/health independent of SMTP reachability
      MANAGEMENT_HEALTH_MAIL_ENABLED: "false",
      // Secrets and per-deployment values live on Railway: railway variables --set KEY=value
      JWT_SECRET: preserve(),
      // Root secret for derived keys (OTP hashing...), at least 32 characters; required, no default
      APP_KEY: preserve(),
      // Shared with vomatt-web (same value there), at least 32 characters; required under the prod profile
      CLIENT_IP_SECRET: preserve(),
      // Railway ingress CIDRs allowed to set X-Forwarded-For; required under the prod profile
      TRUSTED_PROXIES: preserve(),
      MAIL_USERNAME: preserve(),
      MAIL_PASSWORD: preserve(),
      RESEND_API_KEY: preserve(),
      GOOGLE_CLIENT_ID: preserve(),
      LINE_CHANNEL_ID: preserve(),
      LINE_CHANNEL_SECRET: preserve(),
      APPLE_CLIENT_ID: preserve(),
      CORS_ALLOWED_ORIGINS: preserve(),
      // Railway Bucket for uploaded avatars, created by hand; copy its credentials here (STORAGE_REGION unset: auto)
      STORAGE_ENDPOINT: preserve(),
      STORAGE_BUCKET: preserve(),
      STORAGE_ACCESS_KEY_ID: preserve(),
      STORAGE_SECRET_ACCESS_KEY: preserve(),
      STORAGE_PATH_STYLE: preserve(),
      STORAGE_REGION: preserve(),
    },
  });

  return project("appealing-solace", { resources: [db, cache, api] });
});
