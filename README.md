# Vaadin Ecosystem Build

[![Nightly Build](https://github.com/mstahv/addon-tester/actions/workflows/test-addons.yml/badge.svg?event=schedule)](https://github.com/mstahv/addon-tester/actions/workflows/test-addons.yml)

**[View Latest Test Results](https://github.com/mstahv/addon-tester/actions/workflows/test-addons.yml)** - Download `build-logs` artifact for full report (`results.md`)

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
./AddonTester.java
```

Or explicitly with JBang:

```bash
jbang AddonTester.java
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `-v`, `--vaadin.version` | Vaadin version to test against | latest from Maven Central |
| `-w`, `--work-dir` | Working directory for cloning projects | `work` |
| `-c`, `--clean` | Clean work directory before running | `false` |
| `-p`, `--projects` | Comma-separated list of project names to test | all |
| `-q`, `--quiet-downloads` | Silence Maven download progress messages | `false` |
| `-t`, `--timeout` | Build timeout per project in minutes | `2` |
| `-h`, `--help` | Show help message | |
| `-V`, `--version` | Print version info | |

### Examples

Test against the default Vaadin version:
```bash
./AddonTester.java
```

Test against a specific Vaadin version:
```bash
./AddonTester.java -v 24.6.0
```

Clean the work directory and run fresh:
```bash
./AddonTester.java --clean -v 25.0.5
```

Test only specific projects:
```bash
./AddonTester.java -p hugerte-for-flow,super-fields
```

## Adding Projects

Edit `AddonTester.java` to configure which projects to test. Projects are organized by type:

### Add-ons

Edit the `ADDONS` list:

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
```

### Applications

Edit the `APPS` list:

```java
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
./AddonTester.java -v 25.1.0.beta1

# Test against a snapshot
./AddonTester.java -v 25.1-SNAPSHOT
```

## GitHub Actions

The repository includes a GitHub Actions workflow with:

### Scheduled Runs
- Runs daily at 6:00 UTC against Vaadin `25.0-SNAPSHOT`
- Creates a GitHub issue with `build-failure` label if the build fails
- Watch the repository to get notified of new issues

### Manual Runs
1. Go to **Actions** tab in GitHub
2. Select **Test Add-ons** workflow
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
