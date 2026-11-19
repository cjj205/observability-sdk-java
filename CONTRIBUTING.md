# Contributing to observability-sdk-java

## Prerequisites

- JDK 17 or later
- Maven
- Git

## Local verification

Run the full build before opening a pull request:

```powershell
mvn -B -ntp -Pexamples clean verify
```

The build checks dependency upper bounds, source hygiene, unit tests, examples, source/Javadoc artifacts, and the packaged signal-definition YAML files.

For API compatibility after the first released version:

```powershell
mvn -B -ntp -Papi-compatibility '-Dapi.baseline.version=0.1.0' verify
```

## API and signal-definition changes

- Keep `net.openan.observability` as the supported public API surface. Do not expose types from `internal` packages.
- Add Javadoc to public classes and methods.
- Add or update tests for accepted and rejected signal cases.
- When changing `a2at.yaml`, `workflow.yaml`, or `agent.yaml`, increment that file's version and update both language versions of the API reference and development guide.
- Treat removal or tightening of a published signal name, attribute, type, requiredness, or metric dimension as a breaking change.

## Pull requests and commits

- Use Conventional Commit messages, for example `feat(sdk): add exporter option` or `fix(validation): reject invalid metric dimension`.
- Sign every commit off with DCO: `git commit -s -m "..."`.
- Describe the change, compatibility impact, and verification results in the pull request.
- Do not add release tags, publish Maven artifacts, or send release notifications as a side effect of local verification.
