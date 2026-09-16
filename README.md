# BudgetController

BudgetController is a Java-based restaurant management and POS project that I have been developing for a real restaurant use case.

It started as a desktop application with Swing and MySQL. As the project grew, I added a REST API, PWA access, multi-terminal support, database migrations, kitchen printer integration, backup features, and additional security controls.

The main parts of the system are order and table management, products, users and roles, payments, kitchen routing, and basic reporting.

## Technologies

- Java 22
- Swing and FlatLaf
- Javalin and Jetty
- MySQL 8.4
- JDBC and HikariCP
- Jackson and Gson
- JUnit 5 and H2
- Maven
- BCrypt
- OWASP Dependency-Check
- CycloneDX

## How the system is used

The desktop application is the main client. Data is stored in MySQL, and the Javalin API is used by the PWA and other clients.

Kitchen orders can be sent to network-connected ESC/POS printers. Product categories can be mapped to different printers so that an order is printed in the correct kitchen.

The project also supports multiple terminals connected to the same database.

## Database

The database schema is managed with versioned migrations.

The application checks the schema version during startup. Schema changes are applied separately instead of being performed automatically while the application is running.

Database credentials are kept outside the repository:

```text
~/.budget/db.properties
```

The full setup process is documented in [KURULUM_REHBERI.md](docs/KURULUM_REHBERI.md).

## Build and test

Requirements:

- Java 22
- Maven
- MySQL 8.x

Run the tests:

```bash
mvn test
```

Build the project:

```bash
mvn clean package
```

Run the dependency scan:

```bash
mvn org.owasp:dependency-check-maven:check
```

## Documentation

- [Installation guide](docs/KURULUM_REHBERI.md)
- [Kitchen printer setup](docs/MUTFAK_YAZICI_KURULUM.md)
- [Multi-screen setup](docs/COKLU_EKRAN_KURULUM.md)
- [Cloud backup notes](docs/BULUT_YEDEKLEME.md)

The project is still being developed and tested before being used in the restaurant.
