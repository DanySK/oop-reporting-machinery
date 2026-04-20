# Agent Instructions

## Scope

- Keep changes small and repository-local. This project is primarily the single Kotlin script [`process.main.kts`](process.main.kts) plus the workflow templates it copies into downstream repositories.
- Preserve the existing structure and naming. Do not introduce extra modules, helper scripts, or alternate automation unless the change requires them.

## Build And Validation

- Use the Gradle wrapper for repository tasks. Run `./gradlew build` before finishing.
- If the default Gradle home is not writable in the current environment, use `GRADLE_USER_HOME=.gradle-user-home ./gradlew build`.
- If the wrapper needs network access or another unavailable capability, report that verification could not be completed instead of claiming success.

## Repository-Specific Rules

- Keep `Dockerfile` and Java-related automation aligned. The CI workflow reads the JDK version from `Dockerfile`, so do not change one without the other.
- Treat `.github/workflows/oop-*.yml` as templates for generated repositories. Update those files when the downstream copied workflows must change.
- Treat `.github/workflows/*.yml` without the `oop-` prefix as this repository's own CI/CD configuration. Do not let the template and local workflow variants drift unintentionally.
- Do not commit transient runtime artifacts such as `scan-journal.log`, `.gradle/`, or `.gradle-user-home/` changes unless the user explicitly asks for them.

## Editing Guidance

- Prefer direct edits to `process.main.kts` over adding parallel implementations.
- Keep command behavior explicit and deterministic. This script manages GitHub forks, workflow copying, and build-file rewriting, so avoid hidden side effects or unrelated refactors.
- Treat warning suppressions as a last resort. Every suppression must include a short justification next to the suppression site.

## Commits

- When a commit message is needed, use the conventional-commit header format `type: summary` or `type(scope): summary`.
- Match the repository's existing usage:
  `feat:` for new behavior,
  `fix:` for bug fixes,
  `ci:` for workflow or automation changes,
  `build:` for build-system changes,
  `chore(deps):` for dependency and plugin version updates,
  `ci(deps):` for GitHub Actions and CI dependency updates.
- Write the summary in the imperative mood and keep it specific, for example `fix: skip first argument in project slug processing`.
