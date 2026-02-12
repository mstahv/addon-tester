# Vaadin Add-on Compatibility Tester

A JBang script for testing Vaadin add-ons against different Vaadin framework versions.

## Prerequisites

- Java 21 or higher
- [JBang](https://www.jbang.dev/) installed
- Git
- Maven
- [SDKMAN](https://sdkman.io/) (optional, for per-addon Java version switching)

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
| `-v`, `--vaadin.version` | Vaadin version to test against | `25.0.5` |
| `-w`, `--work-dir` | Working directory for cloning projects | `work` |
| `-c`, `--clean` | Clean work directory before running | `false` |
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

## Adding Add-ons

Edit the `ADDONS` list in `AddonTester.java` to configure which add-ons to test:

```java
private static final List<AddonConfig> ADDONS = List.of(
    // Simple: just name and repo URL
    new AddonConfig("addon-name", "https://github.com/org/repo"),
    // With build subdirectory
    new AddonConfig("multi-module", "https://github.com/org/repo", "submodule"),
    // With subdirectory and specific Java version (via SDKMAN)
    new AddonConfig("legacy-addon", "https://github.com/org/repo", "subdir", "17-tem"),
    // Full configuration
    new AddonConfig("full-config", "https://github.com/org/repo",
                    "branch-name", "subdir", "21-tem", List.of("-DskipTests"), false, null)
);
```

### AddonConfig Fields

- `name` - Directory name for the cloned repository
- `repoUrl` - Git repository URL
- `branch` - Branch to checkout (optional, defaults to main)
- `buildSubdir` - Subdirectory to run Maven in (optional, for multi-module projects)
- `javaVersion` - SDKMAN Java version identifier (optional, e.g., `"21-tem"` for Temurin 21, auto-installs if missing)
- `extraMvnArgs` - Additional Maven arguments (optional)
- `ignored` - Skip this add-on if true
- `ignoreReason` - Reason for ignoring (shown in output)

## How It Works

1. Clones (or updates) each configured add-on repository into the work directory
2. Auto-detects the default branch (main/master/etc.) from the remote
3. Installs and switches Java version via SDKMAN if configured (auto-installs if missing)
4. Runs `mvn clean verify -Dvaadin.version=<version>` for each add-on
5. Displays a live status table with build progress (last 10 lines of output)
6. Saves full build logs to `work/<addon-name>-build.log`
7. Reports success/failure status with colored output and timing information
8. Returns exit code 0 if all tests pass, 1 otherwise
