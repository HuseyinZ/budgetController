# budgetController — Autonomous Agent Rules

## Mission

Continuously improve this Java/Swing + Javalin REST + PWA + MySQL POS application through:

- behavior-preserving clean-code work
- bounded product corrections
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

No human approval is required for authorized LOW-risk work.

### MEDIUM risk

The agent may autonomously implement and squash merge only when every condition below is satisfied:

1. the issue author is exactly `HuseyinZ`
2. the issue is open
3. the issue has label `agent:auto-medium`
4. the issue body contains a separate exact line `Risk: MEDIUM`
5. the issue body contains an `Allowed-Paths:` block with exact repository-relative paths
6. every changed file is contained in that block
7. no hard-protected area is changed
8. the total change is at most 5 files and 400 added plus deleted lines
9. exactly three visible Codex → Claude review rounds complete
10. all three Claude verdicts are PASS and bound to the matching round and exact HEAD SHA
11. all required checks pass on the final HEAD
12. the authorization body and allowed-path set remain unchanged throughout the run
13. the final HEAD is current with the base branch and is squash merged using the expected SHA

No human approval is required after these machine-enforced conditions are satisfied.

When a MEDIUM review finds no justified code change in Round 2 or Round 3, the controller may create an empty verification commit solely to produce a new immutable HEAD SHA for the next independent review.

### HIGH risk

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

- bounded AppState refactoring that preserves external behavior
- bounded ApiServer handler refactoring that preserves the API contract
- small PWA mutation or navigation-flow corrections
- small Swing table/order behavior corrections
- validation changes affecting accepted requests when explicitly scoped
- stock, totals, history, printing, or order-log corrections that do not change lifecycle identity
- dependency version updates outside build infrastructure
- dead-code deletion with uncertain callers after exhaustive caller checks

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

## Hard-protected areas

Never modify or auto-merge these areas through LOW or MEDIUM autonomy:

- authentication, authorization, session, token, password, permission, backup, restore, and encryption code
- SQL, DDL, migration, trigger, and production database bootstrap files
- order-item mutation identity or legacy identity fallback behavior
- production deployment configuration
- `.github/workflows/**`
- `AGENTS.md`
- `pom.xml`
- `owasp-suppressions.xml`
- build wrappers and executable scripts
- credential, certificate, key, environment, backup, export, archive, PDF, or Office files

Changes to these areas require controlled infrastructure bootstrap or human-directed work and must never be performed by the runtime controller.

## Bounded MEDIUM areas

These areas may be modified only through authorized MEDIUM mode and only when named exactly in `Allowed-Paths:`:

- `src/main/java/state/AppState.java`
- `src/main/java/service/api/ApiServer.java`
- PWA JavaScript/HTML/CSS files
- Swing UI files
- non-security service logic
- non-mutating DAO reads
- tests and documentation supporting the scoped change

DAO mutation logic remains HIGH unless the issue is explicitly reclassified by a future policy change.

## MEDIUM three-round protocol

Each round must produce a visible audit trail:

```text
ROUND N — CODEX -> CLAUDE
ROUND N — CLAUDE -> CODEX
ROUND N — CONTROLLER STATUS
```

Claude’s MEDIUM verdict must begin exactly with:

```text
VERDICT: PASS
HEAD_SHA: <40-character SHA>
ROUND: <1|2|3>
RISK: MEDIUM
BEHAVIOR_CHANGE: EXPECTED_AND_SCOPED
```

Use `VERDICT: CHANGES_REQUESTED` when blocking findings exist. A verdict for another round or SHA is invalid.

The final controller comment must begin with:

```text
FINAL MERGE DECISION
```

and record the final HEAD SHA and every required gate.

## Git rules

Never push directly to `master`.

Use a dedicated branch such as:

```text
agent/issue-123
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

Before pushing each implementation or verification round:

```bash
git diff --check
mvn test
mvn clean package -DskipTests
```

All commands must succeed.

For PWA changes, include a focused smoke-test plan in the pull request.

For API changes, include request/response verification.

For MEDIUM work, the controller must use a sanitized environment and must not launch the application, contact a production database, perform restore/deployment operations, or execute generated scripts.

If verification cannot run or fails, do not commit, push, or merge.

## Auto-merge requirements

A LOW pull request may auto-merge only when:

- protected areas were not changed
- all required CI checks pass
- the diff is small and single-purpose
- no secret or sensitive file is present
- no API, database, UI workflow, security, stock, or order behavior changed

A MEDIUM pull request may auto-merge only when:

- issue authorization and `Allowed-Paths:` validation pass
- hard-protected areas were not changed
- three exact round/SHA Claude PASS verdicts exist
- all required checks pass on the final HEAD
- size and file limits pass
- the final diff is single-purpose
- the base is fresh
- the expected final HEAD SHA is squash merged without administrator bypass

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
- do not create unrelated UI sections, badges, or cards

## Reporting

Every pull request must include:

- risk classification
- files changed
- reason for the change
- expected behavior impact
- test and build results
- smoke-test steps when relevant
- explicit statement of hard-protected areas not changed

## Deferred work

Do not start these autonomously:

- AppState add-path common-tail extraction
- setItemNote itemId migration
- Swing itemId migration
- legacy productName fallback removal
- product+note identity
- atomic add+note
- AppState decomposition
- TableOrder / OrderLine.copy dead-code deletion