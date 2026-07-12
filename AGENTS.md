# budgetController — Autonomous Agent Rules

## Mission

Continuously improve this Java/Swing + Javalin REST + PWA + MySQL POS application through:

- behavior-preserving clean-code work
- low-risk security improvements
- tests and documentation
- small, reversible commits

Correctness and restaurant operation safety take priority over cleanup volume.

## Autonomy model

### LOW risk

The agent may autonomously:

1. analyze the task
2. create a dedicated branch
3. edit the code
4. run required verification
5. commit using explicit file paths
6. push the branch
7. open a pull request
8. allow auto-merge only when all required checks pass

No human approval is required for LOW-risk work.

### MEDIUM or HIGH risk

The agent must:

1. perform read-only analysis
2. document the risk and suggested plan
3. create an issue or draft report
4. make no source-code change
5. not merge anything

When uncertain, classify the task at the higher risk level.

## Risk classification

### LOW

Examples:

- unused import removal
- removal of no-op code
- duplicated expression or literal cleanup
- tiny private-helper extraction
- local naming improvements
- documentation and comment correction
- test additions that do not alter production behavior
- removal or masking of nonessential sensitive debug output
- error-message cleanup without changing status codes or control flow

### MEDIUM

Examples:

- AppState refactoring
- ApiServer handler refactoring
- PWA mutation or navigation flow changes
- Swing table/order behavior
- validation changes affecting accepted requests
- stock, totals, history, printing, or order-log paths
- dependency version updates
- dead-code deletion with uncertain callers

### HIGH

Examples:

- authentication or authorization
- session or token handling
- password hashing
- backup encryption or restore behavior
- database schema, migrations, triggers, or production SQL
- breaking API changes
- order-item identity changes
- table/order/stock lifecycle changes
- production deployment configuration
- broad architectural rewrites

## Protected areas

Do not autonomously modify these areas, even for apparent cleanup:

- `src/main/java/state/AppState.java`
- `src/main/java/service/api/ApiServer.java`
- authentication, session, permission, backup, and encryption code
- DAO mutation logic
- SQL, DDL, migration, and trigger files
- `.github/workflows/**`
- production configuration

Changes involving these areas are at least MEDIUM risk and require analysis-only handling.

## Git rules

Never push directly to `master`.

Use a dedicated branch such as:

```text
agent/cleanup-short-description
agent/security-short-description
```

Never use:

```bash
git add .
git add -A
git clean -fd
git push --force
```

Stage only explicit paths.

Do not rewrite history.

Do not delete untracked files without inspecting them first.

Keep each commit and pull request single-purpose.

## Required verification

Before committing:

```bash
git diff --check
mvn test
mvn clean package -DskipTests
```

All commands must succeed.

For PWA changes, include a focused smoke-test plan in the pull request.

For API changes, include request/response verification.

If verification cannot run or fails, do not commit or merge.

## Auto-merge requirements

A pull request may auto-merge only when:

- the task is classified LOW
- protected areas were not changed
- all required CI checks pass
- the diff is small and single-purpose
- no secret or sensitive file is present
- no API, database, UI workflow, security, stock, or order behavior changed

Otherwise leave the pull request unmerged.

## Security rules

Never commit or expose:

- passwords
- tokens
- API keys
- SMTP credentials
- database credentials
- private keys or certificates
- customer or order exports
- production backups
- `.env` files

If a possible secret is detected, stop the task, avoid reproducing the value, and create a security warning.

Low-risk security cleanup may remove sensitive debug output, mask values, or clarify safe error messages.

Do not autonomously change authentication, authorization, session, encryption, CORS, CSP, rate limiting, or permission checks.

## Identity rules

For order-item mutations:

```text
itemId = order_items.id
```

is the mutation identity.

`productName` is display text, except for explicitly documented legacy compatibility.

Never derive mutation identity from DOM text, product names, row indexes, or stripped labels.

Do not expand legacy product-name mutation paths.

## PWA rules

- preserve the existing layout unless explicitly requested
- escape server-provided text before inserting it into HTML
- avoid optimistic updates for order mutations
- refresh from the server after successful mutations
- do not create new UI sections, badges, or cards during cleanup

## Reporting

Every pull request must include:

- risk classification
- files changed
- reason for the change
- confirmation that behavior is unchanged
- test and build results
- smoke-test steps when relevant
- explicit statement of protected areas not changed

## Deferred work

Do not start these as autonomous cleanup:

- AppState add-path common-tail extraction
- setItemNote itemId migration
- Swing itemId migration
- legacy productName fallback removal
- product+note identity
- atomic add+note
- AppState decomposition
- TableOrder / OrderLine.copy dead-code deletion