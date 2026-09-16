# BudgetController

BudgetController is a Java 22 restaurant POS and operations-management application built around a Swing desktop client, a Javalin REST API, PWA assets, and MySQL persistence.

The project is designed as a practical restaurant system rather than a standalone demo: it covers desktop/PWA access, database lifecycle, security controls, multi-screen deployment, and network kitchen-printer integration.

## Highlights

- Restaurant POS and order-management workflow
- Java Swing desktop interface
- Javalin REST API for client integrations
- MySQL 8.4 persistence with HikariCP connection pooling
- Versioned database migrations and startup schema verification
- Role-based access and authentication-related security controls
- Externalized production credentials and least-privilege database access
- PWA assets for browser/mobile access
- Multi-screen / multi-terminal deployment support
- ESC/POS kitchen-printer routing over the local network
- Backup and restore documentation
- OWASP Dependency-Check and CycloneDX SBOM support

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Java 22 |
| Desktop UI | Swing + FlatLaf |
| API | Javalin 6 / Jetty |
| Database | MySQL 8.4 |
| Database access | JDBC + HikariCP |
| JSON | Jackson / Gson |
| Authentication | BCrypt-based password hashing |
| Testing | JUnit 5 + H2 |
| Build | Maven |
| Security tooling | OWASP Dependency-Check |
| Supply-chain metadata | CycloneDX SBOM |

## Architecture

```text
                    ┌──────────────────────┐
                    │   Swing Desktop UI   │
                    └──────────┬───────────┘
                               │
                         Application Layer
                               │
             ┌─────────────────┼─────────────────┐
             │                 │                 │
        JDBC / HikariCP    Javalin REST API   Printing
             │                 │                 │
             ▼                 ▼                 ▼
          MySQL 8.4       PWA / clients     ESC/POS printers
```

## Database Lifecycle

The repository uses versioned schema migrations and verifies the expected schema at startup. Runtime database credentials are kept outside version control.

Production configuration is read from:

```text
~/.budget/db.properties
```

See [KURULUM_REHBERI.md](KURULUM_REHBERI.md) for the full installation and migration workflow.

## Kitchen Printing

The project contains support and documentation for routing order items to network-connected ESC/POS kitchen printers.

See:

- [MUTFAK_YAZICI_KURULUM.md](MUTFAK_YAZICI_KURULUM.md)
- [COKLU_EKRAN_KURULUM.md](COKLU_EKRAN_KURULUM.md)

## Backup

Backup and restore guidance is documented in:

- [BULUT_YEDEKLEME.md](BULUT_YEDEKLEME.md)
- [KURULUM_REHBERI.md](KURULUM_REHBERI.md)

## Build and Verification

### Prerequisites

- Java 22
- Maven
- MySQL 8.x for production use

### Run tests

```bash
mvn test
```

### Build

```bash
mvn clean package
```

### Dependency security scan

```bash
mvn org.owasp:dependency-check-maven:check
```

The build also supports CycloneDX SBOM generation.

## Security Notes

- Do not commit database, email, or other runtime credentials.
- Production secrets are intentionally externalized from the application JAR.
- The project includes dependency-vulnerability scanning and dependency-version controls.
- Database runtime access follows a least-privilege model; schema migration is handled separately.

## Project Status

Active development. The current focus is production hardening, deployment reliability, database migration safety, and operational readiness.
