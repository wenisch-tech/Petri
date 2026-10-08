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

## Secret encryption

Credentials for future repository and model-provider connections are encrypted with AES-256-GCM before persistence. Configure a dedicated, Base64-encoded 32-byte key in every environment that stores credentials; Petri fails closed if a secret operation is attempted without it. Keep the value outside source control and back it up securely, because losing it makes encrypted records unrecoverable.

```bash
export PETRI_SECRETS_MASTER_KEY="$(openssl rand -base64 32)"
```

## OIDC

Local login remains available by default. To enable an OpenID Connect provider, configure a standard Spring Security client registration with `openid` and `email` scopes. Petri requires the provider's `email_verified` claim before it creates or links an account. A verified email matching an existing local account retains that account's Petri role.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: petri
            client-secret: change-me
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: https://sso.example.test/realms/platform
```

## Status

Phase 1 is in progress. The current slices provide the reproducible backend/frontend build, Flyway-managed persistence, local authentication, project registry, audit events, and administrator-managed API keys with BCrypt-only storage, expiry and revocation.

## License

Petri is licensed under the GNU Affero General Public License v3.0.
