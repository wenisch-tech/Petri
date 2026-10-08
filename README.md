# Petri

Petri is a self-hosted, Java-based coding agent for controlled analysis, modification and verification of software projects.

## Development

Requirements: JDK 25 and Maven 3.9+.

```bash
./mvnw test
./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in with `admin@petri.local` / `admin` for local evaluation. Set `PETRI_ADMIN_EMAIL` and `PETRI_ADMIN_PASSWORD` before exposing the service beyond localhost.

The standalone profile stores its H2 database below `data/`. PostgreSQL can be selected through the standard `SPRING_DATASOURCE_*` environment variables.

## Status

Phase 1 is in progress. The current slices provide the reproducible backend/frontend build, Flyway-managed persistence, local authentication, health endpoint, project registry and immutable audit events for project creation.

## License

Petri is licensed under the GNU Affero General Public License v3.0.
