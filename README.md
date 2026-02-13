# Vaadin Ecosystem Build

[![Ecosystem Build](https://github.com/mstahv/vaadin-ecosystem-build/actions/workflows/ecosystem-build.yml/badge.svg?event=schedule)](https://github.com/mstahv/vaadin-ecosystem-build/actions/workflows/ecosystem-build.yml)

**[View Build Status Dashboard](https://ecosystembuild.parttio.org)** - Live results for all tested Vaadin versions

This project continuously validates that Vaadin add-ons and applications build successfully against the latest Vaadin framework versions, including unreleased snapshots. It helps detect compatibility issues early, before they affect end users.

## Add Your Project

**The more projects we test, the better!** Each additional project increases test coverage and helps the Vaadin team catch regressions before releases. When your project is included:

- **Early warning**: You'll know about breaking changes in Vaadin before they're released
- **Help Vaadin**: Your real-world usage patterns help catch edge cases that unit tests miss
- **Stay compatible**: Automatic testing against snapshots means fewer surprises when updating

### What We Test

Even just verifying that your project **compiles** against new Vaadin versions is valuable. But it's even better if your project has actual tests that run during `mvn verify`:

- **Unit tests** - Basic component and logic testing
- **Integration tests** - Spring context, database, etc.
- **E2E tests** - Selenium, TestBench, Playwright, or similar

Tests run on a dedicated GitHub Actions runner with plenty of resources (we can add more if needed). A reasonable build time is a few minutes per project. If your project needs custom Maven flags or profiles, we can configure that.

This is currently an unofficial proof-of-concept by [@mstahv](https://github.com/mstahv), but the plan is to make this an official tool maintained by the Vaadin team in the future.

[Create an issue](https://github.com/mstahv/vaadin-ecosystem-build/issues/new) to propose your add-on or application. Both public add-ons and example applications are welcome.

## About

A JBang script for testing Vaadin ecosystem projects (add-ons and applications) against different Vaadin framework versions.

> **Security Warning:** This tool clones and builds external repositories, executing Maven plugins and code without verification. Run this only in an isolated environment such as a container, VM, or CI runner. Never run on a machine with sensitive data or credentials.

## Prerequisites

- Java 21 or higher
- [JBang](https://www.jbang.dev/) installed
- Git
- Maven
- [SDKMAN](https://sdkman.io/) (optional, for per-project Java version switching)

## Usage

Run the script directly with JBang:

```bash
./EcosystemBuild.java
```

Or explicitly with JBang:

```bash
jbang EcosystemBuild.java
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-v`, `--vaadin.version` | Vaadin version to test against | latest from Maven Central |
| `-w`, `--work-dir` | Working directory for cloning projects | `work` |
| `-c`, `--clean` | Clean version-specific output directory before running | `false` |
| `-p`, `--projects` | Comma-separated list of project names to test | all |
| `-j`, `--buildThreads` | Number of concurrent builds | `1` |
| `-q`, `--quiet-downloads` | Silence Maven download progress messages | `false` |
| `-t`, `--timeout` | Build timeout per project in minutes | `2` |
| `-h`, `--help` | Show help message | |
| `-V`, `--version` | Print version info | |

### Examples

Test against the default Vaadin version:
```bash
./EcosystemBuild.java
```

Test against a specific Vaadin version:
```bash
./EcosystemBuild.java -v 24.6.0
```

Clean the work directory and run fresh:
```bash
./EcosystemBuild.java --clean -v 25.0.5
```

Test only specific projects:
```bash
./EcosystemBuild.java -p hugerte-for-flow,super-fields
```

Run builds in parallel (4 concurrent jobs):
```bash
./EcosystemBuild.java -j 4
```

## Adding Projects

Edit the `ADDONS` and `APPS` lists at the top of `EcosystemBuild.java`:

```java
private static final List<AddonProject> ADDONS = List.of(
    new AddonProject() {{
        name = "my-addon";
        repoUrl = "https://github.com/org/my-addon";
    }},
    new AddonProject() {{
        name = "complex-addon";
        repoUrl = "https://github.com/org/complex-addon";
        buildSubdir = "addon-module";
        javaVersion = "21-tem";
    }}
);

private static final List<AppProject> APPS = List.of(
    new AppProject() {{
        name = "my-app";
        repoUrl = "https://github.com/org/my-vaadin-app";
    }}
);
```

### Project Configuration Fields

| Field | Description |
|-------|-------------|
| `name` | Directory name for the cloned repository |
| `repoUrl` | Git repository URL |
| `branch` | Branch to checkout (optional, auto-detects default) |
| `buildSubdir` | Subdirectory to run Maven in (optional, for multi-module projects) |
| `javaVersion` | SDKMAN Java version identifier (e.g., `"21-tem"`, auto-installs if missing) |
| `useAddonsRepo` | Enable Vaadin Directory repository for dependencies |
| `extraMvnArgs` | Additional Maven arguments |
| `ignored` | Skip this project if true |
| `ignoreReason` | Reason for ignoring (shown in output) |

## Pre-releases and Snapshots

When a custom Vaadin version is specified (via `-v`), the script automatically uses `settings.xml` which includes the Vaadin pre-release and snapshot repositories:

- `https://maven.vaadin.com/vaadin-prereleases` - For beta, RC, and snapshot versions

This allows testing projects against unreleased Vaadin versions:

```bash
# Test against a beta version
./EcosystemBuild.java -v 25.1.0.beta1

# Test against a snapshot
./EcosystemBuild.java -v 25.1-SNAPSHOT
```

## GitHub Actions

The repository includes a GitHub Actions workflow with:

### Scheduled Runs
- **Hourly**: Tests against Vaadin `25.0-SNAPSHOT` to catch regressions early
- **Daily** (6:00 UTC): Tests against the latest stable release from Maven Central
- Runs on self-hosted runner with work directory preserved between runs
- Creates a GitHub issue with `build-failure` label if the build fails
- Issues include the specific Vaadin version for tracking
- Watch the repository to get notified of new issues

### Known Issue Detection
If a project fails but has an open GitHub issue that mentions both the project name and the Vaadin version being tested, it will be marked as "known issue" instead of failing the build. This prevents repeated notifications for already-tracked problems.

### Manual Runs
1. Go to **Actions** tab in GitHub
2. Select **Ecosystem Build** workflow
3. Click **Run workflow**
4. Optionally specify a Vaadin version (defaults to latest stable)
5. Build logs are uploaded as artifacts for inspection

## How It Works

1. Clones (or updates) each configured project repository into the work directory
2. Auto-detects the default branch (main/master/etc.) from the remote
3. Installs and switches Java version via SDKMAN if configured (auto-installs if missing)
4. Updates `vaadin.version` property using Maven versions plugin
5. Runs `mvn clean verify` for each project
6. Displays a live status table grouped by project type with build progress
7. Saves full build logs to `work/<project-name>-build.log`
8. Reports success/failure status with colored output and timing information
9. Returns exit code 0 if all tests pass, 1 otherwise
