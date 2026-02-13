///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

@Command(name = "ecosystem-build", mixinStandardHelpOptions = true, version = "1.0",
        description = "Tests Vaadin ecosystem projects against framework version changes")
public class AddonTester implements Callable<Integer> {

    // ANSI color codes
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String DIM = "\u001B[2m";
    private static final String RESET = "\u001B[0m";
    private static final String CLEAR_LINE = "\u001B[2K";
    private static final String MOVE_UP = "\u001B[1A";

    private static final int TAIL_LINES = 10;

    private static final String FALLBACK_VERSION = "25.0.5";

    @Option(names = {"--vaadin.version", "-v"}, description = "Vaadin version to test against (default: latest from Maven Central)")
    private String vaadinVersion;

    @Option(names = {"--work-dir", "-w"}, description = "Working directory for cloning projects", defaultValue = "work")
    private String workDir;

    @Option(names = {"--clean", "-c"}, description = "Clean work directory before running")
    private boolean clean;

    @Option(names = {"--projects", "-p"}, description = "Comma-separated list of project names to test (default: all)", split = ",")
    private List<String> selectedProjects;

    @Option(names = {"--quiet-downloads", "-q"}, description = "Silence Maven download progress messages")
    private boolean quietDownloads;

    @Option(names = {"--timeout", "-t"}, description = "Build timeout per project in minutes", defaultValue = "2")
    private int timeoutMinutes;

    private boolean useCustomSettings = false;

    // Project types
    enum ProjectType { ADDON, APP }

    // Project configuration base class
    static class AddonProject {
        public String name;
        public String repoUrl;
        public String branch;           // Git branch (null = auto-detect default)
        public String buildSubdir;      // Subdirectory to run Maven in
        public String javaVersion;      // SDKMAN Java version (e.g., "21-tem")
        public boolean useAddonsRepo;   // Enable Vaadin Directory repository
        public List<String> extraMvnArgs = List.of();
        public boolean ignored;
        public String ignoreReason;
    }

    // App project configuration
    static class AppProject {
        public String name;
        public String repoUrl;
        public String branch;           // Git branch (null = auto-detect default)
        public String buildSubdir;      // Subdirectory to run Maven in
        public String javaVersion;      // SDKMAN Java version (e.g., "21-tem")
        public boolean useAddonsRepo;   // Enable Vaadin Directory repository
        public List<String> extraMvnArgs = List.of();
        public boolean ignored;
        public String ignoreReason;
    }

    // Test result
    record TestResult(String projectName, ProjectType type, boolean success, String message, long durationMs, Path logFile) {
        TestResult(String projectName, ProjectType type, boolean success, String message, long durationMs) {
            this(projectName, type, success, message, durationMs, null);
        }
    }

    // Build status for display
    enum BuildStatus { PENDING, BUILDING, PASSED, FAILED, IGNORED }

    // Configure add-ons to test here
    private static final List<AddonProject> ADDONS = List.of(
        new AddonProject() {{
            name = "hugerte-for-flow";
            repoUrl = "https://github.com/parttio/hugerte-for-flow";
        }},
        new AddonProject() {{
            name = "super-fields";
            repoUrl = "https://github.com/vaadin-miki/super-fields";
            buildSubdir = "superfields";
            javaVersion = "21-tem";
        }},
        new AddonProject() {{
            name = "flow-viritin";
            repoUrl = "https://github.com/viritin/flow-viritin";
        }},
        new AddonProject() {{
            name = "grid-pagination";
            repoUrl = "https://github.com/parttio/grid-pagination";
        }},
        new AddonProject() {{
            name = "vaadin-fullcalendar";
            repoUrl = "https://github.com/stefanuebe/vaadin-fullcalendar";
        }},
        new AddonProject() {{
            name = "vaadin-maps-leaflet-flow";
            repoUrl = "https://github.com/xdev-software/vaadin-maps-leaflet-flow";
        }},
        new AddonProject() {{
            name = "vaadin-ckeditor";
            repoUrl = "https://github.com/wontlost-ltd/vaadin-ckeditor";
            ignored = true;
            ignoreReason = "Build too slow - broken vaadin-snapshots repo";
        }},
        new AddonProject() {{
            name = "svg-visualizations";
            repoUrl = "https://github.com/viritin/svg-visualizations";
        }},
        new AddonProject() {{
            name = "maplibre";
            repoUrl = "https://github.com/parttio/maplibre";
        }}
    );

    // Configure apps to test here
    private static final List<AppProject> APPS = List.of(
        new AppProject() {{
            name = "spring-boot-spatial-example";
            repoUrl = "https://github.com/mstahv/spring-boot-spatial-example";
        }}
    );

    private final Map<String, BuildStatus> statusMap = new LinkedHashMap<>();
    private final Map<String, ProjectType> projectTypes = new HashMap<>();
    private final Map<String, Long> durationMap = new HashMap<>();
    private final Map<String, Long> buildStartTimeMap = new HashMap<>();
    private int lastOutputLines = 0;

    @Override
    public Integer call() throws Exception {
        // Resolve Vaadin version if not specified
        if (vaadinVersion == null || vaadinVersion.isBlank()) {
            System.out.println("🔍 Fetching latest Vaadin version from Maven Central...");
            vaadinVersion = fetchLatestVaadinVersion();
            System.out.println("📦 Using Vaadin version: " + vaadinVersion);
            System.out.println();
        } else {
            // Custom version specified - use pre-release settings for snapshots/betas
            useCustomSettings = true;
            System.out.println("📦 Using custom Vaadin version: " + vaadinVersion);
            System.out.println("🔓 Pre-release/snapshot repositories enabled via settings.xml");
            System.out.println();
        }

        Path workPath = Path.of(workDir);

        if (clean && Files.exists(workPath)) {
            System.out.println("🧹 Cleaning work directory...");
            deleteDirectory(workPath);
        }

        Files.createDirectories(workPath);

        // Build list of all projects to test
        List<AddonProject> addonsToTest = ADDONS;
        List<AppProject> appsToTest = APPS;

        // Filter projects if specific ones are requested
        if (selectedProjects != null && !selectedProjects.isEmpty()) {
            addonsToTest = ADDONS.stream()
                    .filter(a -> selectedProjects.contains(a.name))
                    .toList();
            appsToTest = APPS.stream()
                    .filter(a -> selectedProjects.contains(a.name))
                    .toList();
            if (addonsToTest.isEmpty() && appsToTest.isEmpty()) {
                System.err.println("❓ No matching projects found for: " + String.join(", ", selectedProjects));
                var allNames = new ArrayList<String>();
                ADDONS.forEach(a -> allNames.add(a.name));
                APPS.forEach(a -> allNames.add(a.name));
                System.err.println("📋 Available projects: " + allNames);
                return 1;
            }
        }

        // Initialize status map with project types
        for (AddonProject addon : addonsToTest) {
            statusMap.put(addon.name, addon.ignored ? BuildStatus.IGNORED : BuildStatus.PENDING);
            projectTypes.put(addon.name, ProjectType.ADDON);
        }
        for (AppProject app : appsToTest) {
            statusMap.put(app.name, app.ignored ? BuildStatus.IGNORED : BuildStatus.PENDING);
            projectTypes.put(app.name, ProjectType.APP);
        }

        List<TestResult> results = new ArrayList<>();

        // Print initial header
        printHeader();
        printStatusTable();
        System.out.println();

        // Test add-ons
        for (AddonProject addon : addonsToTest) {
            if (addon.ignored) {
                results.add(new TestResult(addon.name, ProjectType.ADDON, false, "Ignored: " + addon.ignoreReason, 0));
                continue;
            }

            statusMap.put(addon.name, BuildStatus.BUILDING);
            buildStartTimeMap.put(addon.name, System.currentTimeMillis());
            clearOutput();
            printHeader();
            printStatusTable();
            System.out.println();

            TestResult result = testProject(addon.name, addon.repoUrl, addon.branch, addon.buildSubdir,
                    addon.javaVersion, addon.useAddonsRepo, addon.extraMvnArgs, ProjectType.ADDON, workPath);
            results.add(result);

            durationMap.put(addon.name, result.durationMs());
            statusMap.put(addon.name, result.success() ? BuildStatus.PASSED : BuildStatus.FAILED);

            clearOutput();
            printHeader();
            printStatusTable();

            if (!result.success()) {
                System.out.println();
                System.out.printf("  %s💥 %s failed. Log: %s%s%n", RED, addon.name, result.logFile(), RESET);
            }
            System.out.println();
        }

        // Test apps
        for (AppProject app : appsToTest) {
            if (app.ignored) {
                results.add(new TestResult(app.name, ProjectType.APP, false, "Ignored: " + app.ignoreReason, 0));
                continue;
            }

            statusMap.put(app.name, BuildStatus.BUILDING);
            buildStartTimeMap.put(app.name, System.currentTimeMillis());
            clearOutput();
            printHeader();
            printStatusTable();
            System.out.println();

            TestResult result = testProject(app.name, app.repoUrl, app.branch, app.buildSubdir,
                    app.javaVersion, app.useAddonsRepo, app.extraMvnArgs, ProjectType.APP, workPath);
            results.add(result);

            durationMap.put(app.name, result.durationMs());
            statusMap.put(app.name, result.success() ? BuildStatus.PASSED : BuildStatus.FAILED);

            clearOutput();
            printHeader();
            printStatusTable();

            if (!result.success()) {
                System.out.println();
                System.out.printf("  %s💥 %s failed. Log: %s%s%n", RED, app.name, result.logFile(), RESET);
            }
            System.out.println();
        }

        // Print final summary
        printFinalSummary(results);

        boolean allPassed = results.stream()
                .filter(r -> !r.message().startsWith("Ignored:"))
                .allMatch(TestResult::success);
        return allPassed ? 0 : 1;
    }

    private void printHeader() {
        System.out.println("=".repeat(60));
        System.out.println("🏗️  Vaadin Ecosystem Build");
        System.out.println("🎯 Testing against Vaadin version: " + CYAN + vaadinVersion + RESET);
        System.out.println("=".repeat(60));
    }

    private void printStatusTable() {
        // Group by project type
        var addons = statusMap.entrySet().stream()
                .filter(e -> projectTypes.get(e.getKey()) == ProjectType.ADDON)
                .toList();
        var apps = statusMap.entrySet().stream()
                .filter(e -> projectTypes.get(e.getKey()) == ProjectType.APP)
                .toList();

        if (!addons.isEmpty()) {
            System.out.println("  " + CYAN + "📦 Add-ons" + RESET);
            for (var entry : addons) {
                printStatusLine(entry.getKey(), entry.getValue());
            }
        }

        if (!apps.isEmpty()) {
            if (!addons.isEmpty()) System.out.println();
            System.out.println("  " + CYAN + "🚀 Applications" + RESET);
            for (var entry : apps) {
                printStatusLine(entry.getKey(), entry.getValue());
            }
        }
    }

    private void printStatusLine(String name, BuildStatus status) {
        Long duration = durationMap.get(name);
        String durationStr = duration != null ? String.format(" (%.1fs)", duration / 1000.0) : "";

        String buildingTime = "";
        Long startTime = buildStartTimeMap.get(name);
        if (status == BuildStatus.BUILDING && startTime != null) {
            long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
            buildingTime = String.format(" (%ds)", elapsedSec);
        }

        String statusStr = switch (status) {
            case PENDING -> DIM + "⏳ PENDING" + RESET;
            case BUILDING -> YELLOW + "🔨 BUILDING..." + buildingTime + RESET;
            case PASSED -> GREEN + "✅ PASSED" + RESET + durationStr;
            case FAILED -> RED + "❌ FAILED" + RESET + durationStr;
            case IGNORED -> DIM + "⏭️  IGNORED" + RESET;
        };

        System.out.printf("    %-28s %s%n", name, statusStr);
    }

    private void clearOutput() {
        // Clear previous output lines
        for (int i = 0; i < lastOutputLines; i++) {
            System.out.print(MOVE_UP + CLEAR_LINE);
        }
        lastOutputLines = 0;
    }

    private void clearTailLines(int lines) {
        for (int i = 0; i < lines; i++) {
            System.out.print(MOVE_UP + CLEAR_LINE);
        }
    }

    private int countOutputLines() {
        // Header (4 lines) + group headers + status table + spacing + 1 empty line
        int lines = 5 + statusMap.size();
        boolean hasAddons = projectTypes.values().stream().anyMatch(t -> t == ProjectType.ADDON);
        boolean hasApps = projectTypes.values().stream().anyMatch(t -> t == ProjectType.APP);
        if (hasAddons) lines++; // Add-ons header
        if (hasApps) lines++; // Apps header
        if (hasAddons && hasApps) lines++; // Spacing between groups
        return lines;
    }

    private TestResult testProject(String name, String repoUrl, String branch, String buildSubdir,
                                    String javaVersion, boolean useAddonsRepo, List<String> extraMvnArgs,
                                    ProjectType type, Path workPath) {
        long startTime = System.currentTimeMillis();
        Path projectPath = workPath.resolve(name);
        Path logFile = workPath.resolve(name + "-build.log");

        try {
            // Clone or update repository
            if (!Files.exists(projectPath)) {
                System.out.println("  " + DIM + "📥 Cloning " + repoUrl + "..." + RESET);
                int cloneResult = runCommandSilent(workPath, logFile, "git", "clone", "--depth", "1", "--single-branch", repoUrl, name);
                if (cloneResult != 0) {
                    return new TestResult(name, type, false, "Failed to clone repository", elapsed(startTime), logFile);
                }
            } else {
                System.out.println("  " + DIM + "🔄 Updating repository..." + RESET);
                // Discard any local changes (e.g., from versions plugin)
                runCommandSilent(projectPath, logFile, "git", "checkout", "--", ".");
                runCommandSilent(projectPath, logFile, "git", "fetch", "--depth", "1");
                // Get the default branch from remote
                String defaultBranchName = branch != null ? branch : getDefaultBranch(projectPath, logFile);
                runCommandSilent(projectPath, logFile, "git", "reset", "--hard", "origin/" + defaultBranchName);
            }

            // Checkout specific branch if configured
            if (branch != null) {
                int checkoutResult = runCommandSilent(projectPath, logFile, "git", "checkout", branch);
                if (checkoutResult != 0) {
                    checkoutResult = runCommandSilent(projectPath, logFile, "git", "checkout", "-b", branch, "origin/" + branch);
                    if (checkoutResult != 0) {
                        return new TestResult(name, type, false, "Failed to checkout branch: " + branch, elapsed(startTime), logFile);
                    }
                }
            }

            // Build with specified Vaadin version
            Path buildPath = buildSubdir != null ? projectPath.resolve(buildSubdir) : projectPath;

            // Update Vaadin version in pom.xml using versions plugin
            List<String> setPropertyArgs = new ArrayList<>();
            setPropertyArgs.add("versions:set-property");
            setPropertyArgs.add("-Dproperty=vaadin.version");
            setPropertyArgs.add("-DnewVersion=" + vaadinVersion);
            setPropertyArgs.add("-DgenerateBackupPoms=false");
            setPropertyArgs.addAll(getCommonMvnArgs());
            System.out.println("  " + DIM + "$ mvn " + String.join(" ", setPropertyArgs) + RESET);
            runMavenSilent(buildPath, logFile, javaVersion, setPropertyArgs);

            // Also try versions:set for direct vaadin-bom references
            List<String> setVersionArgs = new ArrayList<>();
            setVersionArgs.add("versions:set");
            setVersionArgs.add("-DnewVersion=" + vaadinVersion);
            setVersionArgs.add("-DartifactId=vaadin-bom");
            setVersionArgs.add("-DgenerateBackupPoms=false");
            setVersionArgs.addAll(getCommonMvnArgs());
            System.out.println("  " + DIM + "$ mvn " + String.join(" ", setVersionArgs) + RESET);
            runMavenSilent(buildPath, logFile, javaVersion, setVersionArgs);

            // Run the actual build
            List<String> mvnArgs = new ArrayList<>();
            mvnArgs.add("clean");
            mvnArgs.add("verify");
            mvnArgs.addAll(getCommonMvnArgs());
            if (useAddonsRepo) {
                mvnArgs.add("-Pvaadin-addons"); // Enable Vaadin Directory repository
            }
            mvnArgs.addAll(extraMvnArgs);
            System.out.println("  " + DIM + "$ mvn " + String.join(" ", mvnArgs) + RESET);

            int buildResult = runMavenWithTail(buildPath, logFile, javaVersion, mvnArgs);

            if (buildResult == 0) {
                return new TestResult(name, type, true, "Build successful", elapsed(startTime), logFile);
            } else if (buildResult == -1) {
                return new TestResult(name, type, false, "Build timed out after " + timeoutMinutes + " min", elapsed(startTime), logFile);
            } else {
                return new TestResult(name, type, false, "Build failed (exit code: " + buildResult + ")", elapsed(startTime), logFile);
            }

        } catch (Exception e) {
            return new TestResult(name, type, false, "Error: " + e.getMessage(), elapsed(startTime), logFile);
        }
    }

    private String getDefaultBranch(Path repoPath, Path logFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "symbolic-ref", "refs/remotes/origin/HEAD", "--short");
            pb.directory(repoPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String result = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();

            // Result is like "origin/main" or "origin/master", extract just the branch name
            if (result.startsWith("origin/")) {
                return result.substring(7);
            }
        } catch (Exception e) {
            // Ignore, fall back to default
        }
        return "main"; // Default fallback
    }

    private int runCommandSilent(Path workDir, Path logFile, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        }

        return process.waitFor();
    }

    private int runMavenSilent(Path workDir, Path logFile, String javaVersion, List<String> mvnArgs) throws IOException, InterruptedException {
        String mvnCommand = "mvn " + String.join(" ", mvnArgs);

        List<String> command;
        if (javaVersion != null) {
            String sdkmanInit = "export SDKMAN_DIR=\"$HOME/.sdkman\" && source \"$SDKMAN_DIR/bin/sdkman-init.sh\"";
            String sdkInstall = "yes | sdk install java " + javaVersion + " || true";
            String sdkUse = "sdk use java " + javaVersion;
            String fullCommand = sdkmanInit + " && " + sdkInstall + " && " + sdkUse + " && " + mvnCommand;
            command = List.of("bash", "-c", fullCommand);
        } else {
            command = new ArrayList<>();
            command.add("mvn");
            command.addAll(mvnArgs);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        }

        return process.waitFor();
    }

    private int runMavenWithTail(Path workDir, Path logFile, String javaVersion, List<String> mvnArgs) throws IOException, InterruptedException {
        String mvnCommand = "mvn " + String.join(" ", mvnArgs);

        List<String> command;
        if (javaVersion != null) {
            // Use SDKMAN to install (if needed) and set Java version
            // Set non-interactive mode and auto-answer yes
            String sdkmanInit = "export SDKMAN_DIR=\"$HOME/.sdkman\" && source \"$SDKMAN_DIR/bin/sdkman-init.sh\"";
            String sdkInstall = "yes | sdk install java " + javaVersion + " || true";
            String sdkUse = "sdk use java " + javaVersion;
            String fullCommand = sdkmanInit + " && " + sdkInstall + " && " + sdkUse + " && " + mvnCommand;
            command = List.of("bash", "-c", fullCommand);
        } else {
            command = new ArrayList<>();
            command.add("mvn");
            command.addAll(mvnArgs);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));

        Process process = pb.start();

        LinkedList<String> tailBuffer = new LinkedList<>();
        int displayedLines = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            String line;
            while ((line = reader.readLine()) != null) {
                // Write to log file
                writer.write(line);
                writer.newLine();
                writer.flush();

                // Update tail buffer
                tailBuffer.addLast(line);
                if (tailBuffer.size() > TAIL_LINES) {
                    tailBuffer.removeFirst();
                }

                // Clear previous tail display
                clearTailLines(displayedLines);

                // Print current tail
                displayedLines = 0;
                for (String tailLine : tailBuffer) {
                    String truncated = tailLine.length() > 70 ? tailLine.substring(0, 67) + "..." : tailLine;
                    System.out.printf("  %s%s%s%n", DIM, truncated, RESET);
                    displayedLines++;
                }
            }
        }

        // Clear tail after build completes
        clearTailLines(displayedLines);
        lastOutputLines = countOutputLines();

        boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            System.out.println(RED + "⏱️  Build timed out after " + timeoutMinutes + " minutes" + RESET);
            return -1; // Timeout exit code
        }
        return process.exitValue();
    }

    private long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    private List<String> getCommonMvnArgs() {
        List<String> args = new ArrayList<>();
        args.add("-B"); // Batch mode
        if (quietDownloads) args.add("--no-transfer-progress");
        if (useCustomSettings) {
            // Use settings.xml from script directory for pre-release/snapshot repos
            Path settingsPath = Path.of(System.getProperty("user.dir"), "settings.xml");
            if (Files.exists(settingsPath)) {
                args.add("--settings");
                args.add(settingsPath.toAbsolutePath().toString());
            }
        }
        return args;
    }

    private String fetchLatestVaadinVersion() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://repo1.maven.org/maven2/com/vaadin/vaadin-bom/maven-metadata.xml"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse <release> tag from maven-metadata.xml
                Pattern pattern = Pattern.compile("<release>([^<]+)</release>");
                Matcher matcher = pattern.matcher(response.body());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️  Warning: Could not fetch latest version: " + e.getMessage());
        }
        System.err.println("📦 Using fallback version: " + FALLBACK_VERSION);
        return FALLBACK_VERSION;
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private void printFinalSummary(List<TestResult> results) {
        System.out.println("-".repeat(60));
        System.out.println("📁 Build logs saved to: " + workDir + "/");
        System.out.println();

        int passed = 0, failed = 0, ignored = 0;
        for (TestResult result : results) {
            if (result.message().startsWith("Ignored:")) {
                ignored++;
            } else if (result.success()) {
                passed++;
            } else {
                failed++;
            }
        }

        String summaryIcon = failed == 0 ? "🎉" : "💔";
        System.out.printf("%s Total: %d | %s✅ Passed: %d%s | %s❌ Failed: %d%s | ⏭️  Ignored: %d%n",
                summaryIcon,
                results.size(),
                GREEN, passed, RESET,
                failed > 0 ? RED : "", failed, failed > 0 ? RESET : "",
                ignored);
        System.out.println("=".repeat(60));

        // Write markdown report
        writeMarkdownReport(results, passed, failed, ignored);
    }

    private void writeMarkdownReport(List<TestResult> results, int passed, int failed, int ignored) {
        Path reportPath = Path.of(workDir, "results.md");
        try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
            String status = failed == 0 ? "🎉 All tests passed" : "💔 Some tests failed";
            String timestamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";

            writer.write("# Vaadin Ecosystem Build Report\n\n");
            writer.write("**Vaadin Version:** `" + vaadinVersion + "`\n");
            writer.write("**Last Run:** " + timestamp + "\n");
            writer.write("**Status:** " + status + "\n\n");

            // Group by type
            var addonResults = results.stream().filter(r -> r.type() == ProjectType.ADDON).toList();
            var appResults = results.stream().filter(r -> r.type() == ProjectType.APP).toList();

            if (!addonResults.isEmpty()) {
                writer.write("## 📦 Add-ons\n\n");
                writer.write("| Project | Status | Duration |\n");
                writer.write("|---------|--------|----------|\n");
                for (TestResult result : addonResults) {
                    writeResultRow(writer, result);
                }
                writer.write("\n");
            }

            if (!appResults.isEmpty()) {
                writer.write("## 🚀 Applications\n\n");
                writer.write("| Project | Status | Duration |\n");
                writer.write("|---------|--------|----------|\n");
                for (TestResult result : appResults) {
                    writeResultRow(writer, result);
                }
                writer.write("\n");
            }

            writer.write("---\n");
            writer.write("**Summary:** " + results.size() + " total | ✅ " + passed + " passed | ❌ " + failed + " failed | ⏭️ " + ignored + " ignored\n");

            System.out.println("📊 Report saved to: " + reportPath);
        } catch (IOException e) {
            System.err.println("⚠️  Warning: Could not write report: " + e.getMessage());
        }
    }

    private void writeResultRow(BufferedWriter writer, TestResult result) throws IOException {
        String statusEmoji;
        if (result.message().startsWith("Ignored:")) {
            statusEmoji = "⏭️ IGNORED";
        } else if (result.success()) {
            statusEmoji = "✅ PASSED";
        } else {
            statusEmoji = "❌ FAILED";
        }
        String duration = result.durationMs() > 0
                ? String.format("%.1fs", result.durationMs() / 1000.0)
                : "-";
        writer.write("| " + result.projectName() + " | " + statusEmoji + " | " + duration + " |\n");
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new AddonTester()).execute(args);
        System.exit(exitCode);
    }
}
