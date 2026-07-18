# BudgetController

BudgetController is a Java-based restaurant point-of-sale application. It combines a Swing desktop interface, a Javalin REST API, bundled PWA assets, and MySQL persistence.

## Prerequisites

- Java 22
- Maven

## Verification

```bash
mvn test
mvn clean package -DskipTests
```

## Configuration

Runtime database and email configuration must remain outside version control. Do not commit credentials or other secret values.
