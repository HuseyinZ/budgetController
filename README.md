# BudgetController

BudgetController is a restaurant POS and management application written in Java 22. It started as a desktop application and gradually grew to include a REST API, PWA access, MySQL persistence, multi-terminal support, and network kitchen printers.

The main goal of the project is to manage day-to-day restaurant operations from a single system: orders, tables, users, payments, products, kitchen routing, and related administrative tasks.

## Main Features

- Table and order management
- Product and category management
- User roles and authorization
- Payment and sales flow
- Swing desktop interface
- REST API with Javalin
- PWA access for other devices
- MySQL database with HikariCP
- Versioned database migrations
- Network kitchen-printer support with ESC/POS
- Backup and restore utilities
- Dependency scanning with OWASP Dependency-Check

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Java 22 |
| Desktop UI | Swing, FlatLaf |
| API | Javalin, Jetty |
| Database | MySQL 8.4 |
| Database access | JDBC, HikariCP |
| JSON | Jackson, Gson |
| Authentication | BCrypt |
| Testing | JUnit 5, H2 |
| Build | Maven |
| Security | OWASP Dependency-Check |
| SBOM | CycloneDX |

## Architecture

```text
Swing Desktop App
        |
        +---- JDBC / HikariCP ----> MySQL
        |
        +---- Javalin REST API ---> PWA / other clients
        |
        +---- Printing -----------> ESC/POS kitchen printers
```

## Database

The database schema is managed through versioned migrations. The application checks the schema version at startup and does not modify the schema automatically during normal runtime.

Production database credentials are stored outside the repository in:

```text
~/.budget/db.properties
```

More details are available in [KURULUM_REHBERI.md](KURULUM_REHBERI.md).

## Kitchen Printers

Orders can be routed to different kitchen printers according to product category. The current implementation uses network-connected ESC/POS printers.

Related documentation:

- [MUTFAK_YAZICI_KURULUM.md](MUTFAK_YAZICI_KURULUM.md)
- [COKLU_EKRAN_KURULUM.md](COKLU_EKRAN_KURULUM.md)

## Build

Requirements:

- Java 22
- Maven
- MySQL 8.x for production

Run tests:

```bash
mvn test
```

Build the project:

```bash
mvn clean package
```

Run dependency checks:

```bash
mvn org.owasp:dependency-check-maven:check
```

## Notes

- Runtime credentials and other secrets are not stored in the repository.
- Database access uses separate runtime and migration accounts.
- The project is still under active development and is being prepared for real restaurant use.
