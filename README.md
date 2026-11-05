# GG HighTech — Spring Boot backend

A Spring Boot 4 starter (Java 17, Maven). It is **not deployed**, and the
production API is still the FastAPI app in `../backend-fastapi`.

## Run

```bash
./mvnw test                      # tests use in-memory H2, no PostgreSQL needed
SPRING_DATASOURCE_PASSWORD=... ./mvnw spring-boot:run
```

Then open http://localhost:8080/api/v1/health.

Maven doesn't need to be installed: `./mvnw` downloads the version pinned in
`.mvn/wrapper/maven-wrapper.properties`.

## Configuration

`src/main/resources/application.yml` holds the defaults. Environment
variables override them:

| Variable | Default |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/gghightech_dev` |
| `SPRING_DATASOURCE_USERNAME` | `gghightech_app` |
| `SPRING_DATASOURCE_PASSWORD` | none, so it must be set |
| `PORT` | `8080` |

The URL is JDBC format (`jdbc:postgresql://host:port/db`), not the
`postgresql+psycopg2://` format the FastAPI backend uses.

## Database

This app points at the same database as the FastAPI backend, whose
**Alembic migrations own the schema**. Hibernate is set to `ddl-auto: none`,
so it never creates or alters tables. Map JPA entities onto the existing
tables, and make schema changes through Alembic.

## Endpoints

| Path | Purpose |
|---|---|
| `GET /api/v1/health` | Same path and response shape as the FastAPI health check |
| `GET /actuator/health` | Spring's health check. No details are shown, and no other actuator endpoint is exposed |
