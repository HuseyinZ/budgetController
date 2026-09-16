# Security

Security is considered throughout the development of BudgetController, especially around authentication, database access, application configuration, backups, and dependency management.

## Sensitive configuration

Runtime credentials and secrets must not be committed to the repository. Database and email configuration are stored outside the project files.

Example configuration files in the repository must contain placeholder values only.

## Dependency scanning

Dependency checks can be run with:

```bash
mvn org.owasp:dependency-check-maven:check
```

The project also generates a CycloneDX SBOM during the build process.

## Reporting a security issue

If you find a security issue, please report it privately rather than opening a public issue. Include enough information to reproduce and understand the problem without publishing credentials or other sensitive data.
