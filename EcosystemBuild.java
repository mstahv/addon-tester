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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

@Command(name = "ecosystem-build", mixinStandardHelpOptions = true, version = "1.0",
        description = "Tests Vaadin ecosystem projects against framework version changes")
public class EcosystemBuild implements Callable<Integer> {

    // ============================================================
    // PROJECT CONFIGURATION - Edit these lists to add/remove projects
    // ============================================================

    // Default version overrides applied to all projects (unless they specify their own)
    // Projects can override by specifying their own versionOverrides for the same pattern
    private static final Map<String, VersionConfig> DEFAULT_VERSION_OVERRIDES = Map.of(
        "24.*", new VersionConfig() {{ ignored = true; ignoreReason = "v24 testing limited to selected projects"; }}
    );

    private static final List<AddonProject> ADDONS = List.of(
        new AddonProject() {{
            name = "hugerte-for-flow";
            repoUrl = "https://github.com/parttio/hugerte-for-flow";
            versionOverrides = Map.of("24.*", new VersionConfig() {{ branch = "v1"; }});
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
            name = "dramafinder";
            repoUrl = "https://github.com/parttio/dramafinder";
            notifyUsers = List.of("jcgueriaud1");
        }},
        new AddonProject() {{
            name = "sortable-layout";
            repoUrl = "https://github.com/jcgueriaud1/sortable-layout";
            notifyUsers = List.of("jcgueriaud1");
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
        }},
        new AddonProject() {{
            name = "svg-visualizations";
            repoUrl = "https://github.com/viritin/svg-visualizations";
            extraMvnArgs = List.of("-DskipPerformanceValidation=true");
        }},
        new AddonProject() {{
            name = "maplibre";
            repoUrl = "https://github.com/parttio/maplibre";
            versionOverrides = Map.of(
                "25.*", new VersionConfig() {{ branch = "v25"; }},
                "24.*", new VersionConfig() {{ branch = "v24"; }}
            );
        }},
        new AddonProject() {{
            name = "SimpleTimeline";
            repoUrl = "https://github.com/samie/SimpleTimeline";
            notifyUsers = List.of("samie");
            versionOverrides = Map.of("24.*", new VersionConfig());  // Override default: use defaults for 24.*
        }}
    );

    private static final List<AppProject> APPS = List.of(
        new AppProject() {{
            name = "spring-boot-spatial-example";
            repoUrl = "https://github.com/mstahv/spring-boot-spatial-example";
        }},
        new AppProject() {{
            name = "xsd-validator-ui";
            repoUrl = "https://github.com/rucko24/xsd-validator-ui";
            notifyUsers = List.of("rucko24");
        }}
    );

    // ============================================================
    // END OF PROJECT CONFIGURATION
    // ============================================================

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

    @Option(names = {"--clean", "-c"}, description = "Clean version-specific output directory before running")
    private boolean clean;

    @Option(names = {"--projects", "-p"}, description = "Comma-separated list of project names to test (default: all)", split = ",")
    private List<String> selectedProjects;

    @Option(names = {"--quiet-downloads", "-q"}, description = "Silence Maven download progress messages")
    private boolean quietDownloads;

    @Option(names = {"--timeout", "-t"}, description = "Build timeout per project in minutes", defaultValue = "2")
    private int timeoutMinutes;

    @Option(names = {"--buildThreads", "-j"}, description = "Number of concurrent builds (default: 1)", defaultValue = "1")
    private int buildThreads;

    private boolean useCustomSettings = false;
    private Path versionOutputPath;  // Version-specific output directory for logs and reports

    // Project types
    enum ProjectType { SMOKE_TEST, ADDON, APP }

    // Version-specific configuration overrides
    static class VersionConfig {
        String branch;           // Override branch for this version range
        String javaVersion;      // Override Java version (SDKMAN identifier)
        boolean ignored;         // Skip project for this version range
        String ignoreReason;     // Reason for skipping
        List<String> extraMvnArgs; // Override Maven args (null = use default)
    }

    // Project configuration
    static class AddonProject {
        String name;
        String repoUrl;
        String branch;           // Git branch (null = auto-detect default)
        String buildSubdir;      // Subdirectory to run Maven in
        String javaVersion;      // SDKMAN Java version (e.g., "21-tem")
        boolean useAddonsRepo;   // Enable Vaadin Directory repository
        List<String> extraMvnArgs = List.of();
        List<String> notifyUsers = List.of();  // GitHub usernames to mention in issues
        boolean ignored;
        String ignoreReason;
        Map<String, VersionConfig> versionOverrides = Map.of();  // Version pattern -> config
    }

    // App project configuration
    static class AppProject {
        String name;
        String repoUrl;
        String branch;
        String buildSubdir;
        String javaVersion;
        boolean useAddonsRepo;
        List<String> extraMvnArgs = List.of();
        List<String> notifyUsers = List.of();  // GitHub usernames to mention in issues
        boolean ignored;
        String ignoreReason;
        Map<String, VersionConfig> versionOverrides = Map.of();  // Version pattern -> config
    }

    // Test result
    record TestResult(String projectName, ProjectType type, boolean success, String message, long durationMs, Path logFile) {
        TestResult(String projectName, ProjectType type, boolean success, String message, long durationMs) {
            this(projectName, type, success, message, durationMs, null);
        }
    }

    // Build status for display
    enum BuildStatus { PENDING, WAITING, BUILDING, PASSED, FAILED, KNOWN_ISSUE, IGNORED }

    // Metadata about a failed build - used to provide context in GitHub issues
    record FailureMetadata(String repoUrl, String originalVersion, boolean buildsWithOriginal, List<String> notifyUsers) {}

    private final Map<String, BuildStatus> statusMap = new LinkedHashMap<>();
    private final Map<String, ProjectType> projectTypes = new HashMap<>();
    private final Map<String, Long> durationMap = new HashMap<>();
    private final Map<String, Long> buildStartTimeMap = new HashMap<>();
    private final Map<String, String> knownIssueUrls = new HashMap<>();  // Project -> GitHub issue URL
    private final Map<String, FailureMetadata> failureMetadata = new HashMap<>();  // Project -> failure context
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
        Files.createDirectories(workPath);

        // Create version-specific output directory for logs and reports
        versionOutputPath = workPath.resolve(vaadinVersion);

        if (clean && Files.exists(versionOutputPath)) {
            System.out.println("🧹 Cleaning version output directory: " + versionOutputPath);
            deleteDirectory(versionOutputPath);
        }
        Files.createDirectories(versionOutputPath);

        // Run smoke test first to validate Vaadin version and cache artifacts
        System.out.println("🔥 Running smoke test to validate Vaadin " + vaadinVersion + "...");
        System.out.println();
        TestResult smokeTestResult = runSmokeTest(workPath);
        if (!smokeTestResult.success()) {
            System.out.println();
            System.out.printf("%s💥 Smoke test failed! Vaadin %s may not be available or compatible.%s%n",
                    RED, vaadinVersion, RESET);
            System.out.println("   Check " + smokeTestResult.logFile() + " for details.");
            // Still write report with failed smoke test
            long totalTimeMs = System.currentTimeMillis() - System.currentTimeMillis(); // ~0
            printFinalSummary(List.of(smokeTestResult), smokeTestResult.durationMs());
            return 1;
        }
        System.out.println();
        System.out.printf("%s✅ Smoke test passed - Vaadin %s artifacts cached (%.1fs)%s%n",
                GREEN, vaadinVersion, smokeTestResult.durationMs() / 1000.0, RESET);
        System.out.println();

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

        // Initialize status map with project types (applying version-specific ignore status)
        for (AddonProject addon : addonsToTest) {
            VersionConfig vc = findVersionConfig(addon.versionOverrides, vaadinVersion);
            boolean ignored = (vc != null && vc.ignored) || addon.ignored;
            statusMap.put(addon.name, ignored ? BuildStatus.IGNORED : BuildStatus.PENDING);
            projectTypes.put(addon.name, ProjectType.ADDON);
        }
        for (AppProject app : appsToTest) {
            VersionConfig vc = findVersionConfig(app.versionOverrides, vaadinVersion);
            boolean ignored = (vc != null && vc.ignored) || app.ignored;
            statusMap.put(app.name, ignored ? BuildStatus.IGNORED : BuildStatus.PENDING);
            projectTypes.put(app.name, ProjectType.APP);
        }

        List<TestResult> results = Collections.synchronizedList(new ArrayList<>());
        results.add(smokeTestResult);  // Include smoke test in report
        long buildStartTime = System.currentTimeMillis();

        // Print initial header
        printHeader();
        printStatusTable();
        System.out.println();

        // Collect all projects to build
        record BuildTask(String name, String repoUrl, String branch, String buildSubdir,
                         String javaVersion, boolean useAddonsRepo, List<String> extraMvnArgs,
                         List<String> notifyUsers, ProjectType type, boolean ignored, String ignoreReason) {}

        List<BuildTask> allTasks = new ArrayList<>();
        for (AddonProject addon : addonsToTest) {
            // Apply version-specific overrides if any
            VersionConfig vc = findVersionConfig(addon.versionOverrides, vaadinVersion);
            String branch = (vc != null && vc.branch != null) ? vc.branch : addon.branch;
            String javaVersion = (vc != null && vc.javaVersion != null) ? vc.javaVersion : addon.javaVersion;
            List<String> extraMvnArgs = (vc != null && vc.extraMvnArgs != null) ? vc.extraMvnArgs : addon.extraMvnArgs;
            boolean ignored = (vc != null && vc.ignored) || addon.ignored;
            String ignoreReason = (vc != null && vc.ignored) ? vc.ignoreReason : addon.ignoreReason;

            allTasks.add(new BuildTask(addon.name, addon.repoUrl, branch, addon.buildSubdir,
                    javaVersion, addon.useAddonsRepo, extraMvnArgs, addon.notifyUsers,
                    ProjectType.ADDON, ignored, ignoreReason));
        }
        for (AppProject app : appsToTest) {
            // Apply version-specific overrides if any
            VersionConfig vc = findVersionConfig(app.versionOverrides, vaadinVersion);
            String branch = (vc != null && vc.branch != null) ? vc.branch : app.branch;
            String javaVersion = (vc != null && vc.javaVersion != null) ? vc.javaVersion : app.javaVersion;
            List<String> extraMvnArgs = (vc != null && vc.extraMvnArgs != null) ? vc.extraMvnArgs : app.extraMvnArgs;
            boolean ignored = (vc != null && vc.ignored) || app.ignored;
            String ignoreReason = (vc != null && vc.ignored) ? vc.ignoreReason : app.ignoreReason;

            allTasks.add(new BuildTask(app.name, app.repoUrl, branch, app.buildSubdir,
                    javaVersion, app.useAddonsRepo, extraMvnArgs, app.notifyUsers,
                    ProjectType.APP, ignored, ignoreReason));
        }

        if (buildThreads == 1) {
            // Sequential execution with live output
            for (BuildTask task : allTasks) {
                if (task.ignored) {
                    results.add(new TestResult(task.name, task.type, false, "Ignored: " + task.ignoreReason, 0));
                    continue;
                }

                statusMap.put(task.name, BuildStatus.BUILDING);
                buildStartTimeMap.put(task.name, System.currentTimeMillis());
                clearOutput();
                printHeader();
                printStatusTable();
                System.out.println();

                TestResult result = testProject(task.name, task.repoUrl, task.branch, task.buildSubdir,
                        task.javaVersion, task.useAddonsRepo, task.extraMvnArgs, task.type, workPath);
                results.add(result);

                durationMap.put(task.name, result.durationMs());
                BuildStatus finalStatus;
                if (result.success()) {
                    finalStatus = BuildStatus.PASSED;
                } else {
                    String issueUrl = findOpenGitHubIssue(task.name, vaadinVersion);
                    if (issueUrl != null) {
                        finalStatus = BuildStatus.KNOWN_ISSUE;
                        knownIssueUrls.put(task.name, issueUrl);
                    } else {
                        finalStatus = BuildStatus.FAILED;
                    }

                    // For failures, verify if project builds with its original Vaadin version
                    Path projectPath = workPath.resolve(task.name);
                    Path buildPath = task.buildSubdir != null ? projectPath.resolve(task.buildSubdir) : projectPath;
                    System.out.println();
                    System.out.printf("  %s🔍 Verifying build with project's original Vaadin version...%s%n", DIM, RESET);

                    String originalVersion = detectProjectVaadinVersion(buildPath, task.javaVersion);
                    Path verifyLog = versionOutputPath.resolve(task.name + "-original-build.log");
                    Files.deleteIfExists(verifyLog);
                    boolean buildsWithOriginal = verifyWithOriginalVersion(buildPath, task.javaVersion,
                            task.useAddonsRepo, task.extraMvnArgs, verifyLog);

                    failureMetadata.put(task.name, new FailureMetadata(task.repoUrl, originalVersion, buildsWithOriginal, task.notifyUsers));

                    if (originalVersion != null) {
                        System.out.printf("  %s   Original version: %s, builds: %s%s%n", DIM, originalVersion,
                                buildsWithOriginal ? "✅" : "❌", RESET);
                    }
                }
                statusMap.put(task.name, finalStatus);

                clearOutput();
                printHeader();
                printStatusTable();

                if (finalStatus == BuildStatus.FAILED) {
                    System.out.println();
                    System.out.printf("  %s💥 %s failed. Log: %s%s%n", RED, task.name, result.logFile(), RESET);
                } else if (finalStatus == BuildStatus.KNOWN_ISSUE) {
                    System.out.println();
                    System.out.printf("  %s⚠️  %s failed (known issue). Log: %s%s%n", YELLOW, task.name, result.logFile(), RESET);
                }
                System.out.println();
            }
        } else {
            // Concurrent execution with fixed builder slots
            ExecutorService executor = Executors.newFixedThreadPool(buildThreads);
            List<Future<TestResult>> futures = new ArrayList<>();

            // Fixed slots for each builder thread - index is slot number
            record BuilderSlot(String projectName, Path logFile) {}
            BuilderSlot[] builderSlots = new BuilderSlot[buildThreads];
            Object slotsLock = new Object();

            // Track available slot numbers
            Queue<Integer> availableSlots = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < buildThreads; i++) {
                availableSlots.add(i);
            }

            // Handle ignored tasks first
            for (BuildTask task : allTasks) {
                if (task.ignored) {
                    results.add(new TestResult(task.name, task.type, false, "Ignored: " + task.ignoreReason, 0));
                }
            }

            // Mark non-ignored tasks as WAITING
            for (BuildTask task : allTasks) {
                if (!task.ignored) {
                    statusMap.put(task.name, BuildStatus.WAITING);
                }
            }

            // Submit build tasks
            final Path finalWorkPath = workPath;
            for (BuildTask task : allTasks) {
                if (task.ignored) continue;

                futures.add(executor.submit(() -> {
                    // Acquire a slot for this builder
                    Integer slot = availableSlots.poll();
                    if (slot == null) slot = 0; // Fallback

                    // Mark as building when actually starting
                    synchronized (slotsLock) {
                        statusMap.put(task.name, BuildStatus.BUILDING);
                        buildStartTimeMap.put(task.name, System.currentTimeMillis());
                        builderSlots[slot] = new BuilderSlot(task.name, finalWorkPath.resolve(task.name + "-build.log"));
                    }

                    TestResult result = testProject(task.name, task.repoUrl, task.branch, task.buildSubdir,
                            task.javaVersion, task.useAddonsRepo, task.extraMvnArgs, task.type, finalWorkPath, true);

                    // For failures, verify if project builds with its original Vaadin version
                    FailureMetadata metadata = null;
                    if (!result.success()) {
                        Path projectPath = finalWorkPath.resolve(task.name);
                        Path buildPath = task.buildSubdir != null ? projectPath.resolve(task.buildSubdir) : projectPath;
                        String originalVersion = detectProjectVaadinVersion(buildPath, task.javaVersion);
                        Path verifyLog = versionOutputPath.resolve(task.name + "-original-build.log");
                        Files.deleteIfExists(verifyLog);
                        boolean buildsWithOriginal = verifyWithOriginalVersion(buildPath, task.javaVersion,
                                task.useAddonsRepo, task.extraMvnArgs, verifyLog);
                        metadata = new FailureMetadata(task.repoUrl, originalVersion, buildsWithOriginal, task.notifyUsers);
                    }

                    synchronized (slotsLock) {
                        durationMap.put(task.name, result.durationMs());
                        BuildStatus finalStatus;
                        if (result.success()) {
                            finalStatus = BuildStatus.PASSED;
                        } else {
                            String issueUrl = findOpenGitHubIssue(task.name, vaadinVersion);
                            if (issueUrl != null) {
                                finalStatus = BuildStatus.KNOWN_ISSUE;
                                knownIssueUrls.put(task.name, issueUrl);
                            } else {
                                finalStatus = BuildStatus.FAILED;
                            }
                            if (metadata != null) {
                                failureMetadata.put(task.name, metadata);
                            }
                        }
                        statusMap.put(task.name, finalStatus);
                        builderSlots[slot] = null;
                    }

                    // Release the slot
                    availableSlots.add(slot);

                    return result;
                }));
            }

            // Calculate lines per builder based on terminal size if available
            int terminalHeight = getTerminalHeight();
            int headerAndStatusLines = countOutputLines() + 2; // +2 for empty line and separator
            int availableLines = terminalHeight > 0
                    ? Math.max(buildThreads * 4, terminalHeight - headerAndStatusLines - 2)
                    : TAIL_LINES;
            int linesPerBuilder = Math.max(3, availableLines / buildThreads);

            // Update status display while waiting
            while (!futures.stream().allMatch(Future::isDone)) {
                clearOutput();
                printHeader();
                printStatusTable();
                System.out.println();

                // Show output areas for all builder slots (fixed layout)
                System.out.println("  " + CYAN + "─── Build Output " + "─".repeat(40) + RESET);
                synchronized (slotsLock) {
                    for (int slot = 0; slot < buildThreads; slot++) {
                        String builderId = String.format("Builder %d", slot + 1);
                        BuilderSlot slotInfo = builderSlots[slot];
                        if (slotInfo != null) {
                            System.out.println("  " + YELLOW + "▶ [" + builderId + "] " + slotInfo.projectName() + RESET);
                            printLogTail(slotInfo.logFile(), linesPerBuilder);
                        } else {
                            // Empty slot - always show with reserved space
                            System.out.println("  " + DIM + "▷ [" + builderId + "] (idle)" + RESET);
                            for (int i = 0; i < linesPerBuilder; i++) {
                                System.out.println();
                            }
                        }
                    }
                }

                // Track total lines printed for clearing (fixed size now)
                // Header + status table + 1 empty + separator + (buildThreads * (header + linesPerBuilder))
                lastOutputLines = countOutputLines() + 1 + (buildThreads * (1 + linesPerBuilder));

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Collect results
            for (Future<TestResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    // Error already handled in the task
                }
            }

            executor.shutdown();

            // Final status update
            clearOutput();
            printHeader();
            printStatusTable();

            // Show failures and known issues
            for (TestResult result : results) {
                if (!result.success() && !result.message().startsWith("Ignored:")) {
                    BuildStatus status = statusMap.get(result.projectName());
                    if (status == BuildStatus.KNOWN_ISSUE) {
                        System.out.println();
                        System.out.printf("  %s⚠️  %s failed (known issue). Log: %s%s%n", YELLOW, result.projectName(), result.logFile(), RESET);
                    } else {
                        System.out.println();
                        System.out.printf("  %s💥 %s failed. Log: %s%s%n", RED, result.projectName(), result.logFile(), RESET);
                    }
                }
            }
            System.out.println();
        }

        // Print final summary
        long totalTimeMs = System.currentTimeMillis() - buildStartTime;
        printFinalSummary(results, totalTimeMs);

        // Write list of failed projects for CI integration
        writeFailedProjectsList();

        // Count failures (known issues don't count as failures)
        boolean allPassed = statusMap.values().stream()
                .noneMatch(s -> s == BuildStatus.FAILED);
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
            case WAITING -> CYAN + "⏳ WAITING..." + RESET;
            case BUILDING -> YELLOW + "🔨 BUILDING..." + buildingTime + RESET;
            case PASSED -> GREEN + "✅ PASSED" + RESET + durationStr;
            case FAILED -> RED + "❌ FAILED" + RESET + durationStr;
            case KNOWN_ISSUE -> YELLOW + "⚠️  KNOWN ISSUE" + RESET + durationStr;
            case IGNORED -> DIM + "⏭️  IGNORED" + RESET;
        };

        System.out.printf("    %-28s %s%n", name, statusStr);
    }

    private void printLogTail(Path logFile, int lines) {
        try {
            if (!Files.exists(logFile)) {
                System.out.println("    " + DIM + "(waiting for output...)" + RESET);
                return;
            }
            List<String> allLines = Files.readAllLines(logFile);
            int start = Math.max(0, allLines.size() - lines);
            for (int i = start; i < allLines.size(); i++) {
                String line = allLines.get(i);
                if (line.length() > 100) {
                    line = line.substring(0, 97) + "...";
                }
                System.out.println("    " + DIM + line + RESET);
            }
            // Pad with empty lines if not enough output yet
            for (int i = allLines.size(); i < lines; i++) {
                System.out.println();
            }
        } catch (IOException e) {
            System.out.println("    " + DIM + "(reading log...)" + RESET);
        }
    }

    private int getTerminalHeight() {
        // Try to get terminal height using tput
        try {
            ProcessBuilder pb = new ProcessBuilder("tput", "lines");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && process.waitFor(1, TimeUnit.SECONDS)) {
                    return Integer.parseInt(line.trim());
                }
            }
        } catch (Exception e) {
            // Fallback: try LINES environment variable
            String lines = System.getenv("LINES");
            if (lines != null) {
                try {
                    return Integer.parseInt(lines.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0; // Unknown, use default
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

    private TestResult runSmokeTest(Path workPath) {
        long startTime = System.currentTimeMillis();
        String name = "vaadin-project-archetype";
        Path smokeTestPath = workPath.resolve("smoke-test");
        Path logFile = versionOutputPath.resolve("smoke-test-build.log");

        try {
            // Clear previous log file so re-builds start fresh
            Files.deleteIfExists(logFile);

            // Clean up previous smoke test
            if (Files.exists(smokeTestPath)) {
                deleteDirectory(smokeTestPath);
            }

            // Generate project from archetype
            System.out.println("  " + DIM + "📦 Generating project from Vaadin archetype..." + RESET);
            List<String> archetypeArgs = List.of(
                    "archetype:generate",
                    "-B",  // Batch mode
                    "-DarchetypeGroupId=com.vaadin",
                    "-DarchetypeArtifactId=vaadin-archetype-application",
                    "-DarchetypeVersion=LATEST",
                    "-DgroupId=com.example",
                    "-DartifactId=smoke-test",
                    "-Dversion=1.0-SNAPSHOT"
            );

            List<String> mvnCmd = new ArrayList<>();
            mvnCmd.add("mvn");
            mvnCmd.addAll(archetypeArgs);
            mvnCmd.addAll(getCommonMvnArgs());
            System.out.println("  " + DIM + "$ " + String.join(" ", mvnCmd) + RESET);

            int archetypeResult = runMavenSilent(workPath, logFile, null, archetypeArgs);
            if (archetypeResult != 0) {
                System.out.println("  " + RED + "❌ Archetype generation failed" + RESET);
                return new TestResult(name, ProjectType.SMOKE_TEST, false, "Archetype generation failed", elapsed(startTime), logFile);
            }

            // Set Vaadin version (in case archetype version differs from target version)
            System.out.println("  " + DIM + "🔧 Setting Vaadin version to " + vaadinVersion + "..." + RESET);
            List<String> setPropertyArgs = new ArrayList<>();
            setPropertyArgs.add("versions:set-property");
            setPropertyArgs.add("-Dproperty=vaadin.version");
            setPropertyArgs.add("-DnewVersion=" + vaadinVersion);
            setPropertyArgs.add("-DgenerateBackupPoms=false");
            setPropertyArgs.addAll(getCommonMvnArgs());
            runMavenSilent(smokeTestPath, logFile, null, setPropertyArgs);

            // Run verify to download all dependencies and compile
            System.out.println("  " + DIM + "🔨 Building smoke test project..." + RESET);
            List<String> verifyArgs = new ArrayList<>();
            verifyArgs.add("clean");
            verifyArgs.add("verify");
            verifyArgs.add("-DskipTests");  // Skip tests for smoke test, just need compilation
            verifyArgs.addAll(getCommonMvnArgs());

            List<String> verifyCmdDisplay = new ArrayList<>();
            verifyCmdDisplay.add("mvn");
            verifyCmdDisplay.addAll(verifyArgs);
            System.out.println("  " + DIM + "$ " + String.join(" ", verifyCmdDisplay) + RESET);

            int verifyResult = runMavenSilent(smokeTestPath, logFile, null, verifyArgs);
            if (verifyResult != 0) {
                System.out.println("  " + RED + "❌ Smoke test build failed" + RESET);
                return new TestResult(name, ProjectType.SMOKE_TEST, false, "Build failed", elapsed(startTime), logFile);
            }

            return new TestResult(name, ProjectType.SMOKE_TEST, true, "Build successful", elapsed(startTime), logFile);

        } catch (Exception e) {
            System.out.println("  " + RED + "❌ Smoke test error: " + e.getMessage() + RESET);
            return new TestResult(name, ProjectType.SMOKE_TEST, false, "Error: " + e.getMessage(), elapsed(startTime), logFile);
        }
    }

    private TestResult testProject(String name, String repoUrl, String branch, String buildSubdir,
                                    String javaVersion, boolean useAddonsRepo, List<String> extraMvnArgs,
                                    ProjectType type, Path workPath) {
        return testProject(name, repoUrl, branch, buildSubdir, javaVersion, useAddonsRepo, extraMvnArgs, type, workPath, false);
    }

    private TestResult testProject(String name, String repoUrl, String branch, String buildSubdir,
                                    String javaVersion, boolean useAddonsRepo, List<String> extraMvnArgs,
                                    ProjectType type, Path workPath, boolean silent) {
        long startTime = System.currentTimeMillis();
        Path projectPath = workPath.resolve(name);
        Path logFile = versionOutputPath.resolve(name + "-build.log");

        try {
            // Clear previous log file so re-builds start fresh
            Files.deleteIfExists(logFile);

            // Clone or update repository
            if (!Files.exists(projectPath)) {
                if (!silent) System.out.println("  " + DIM + "📥 Cloning " + repoUrl + "..." + RESET);
                List<String> cloneCmd = new ArrayList<>(List.of("git", "clone", "--depth", "1", "--single-branch"));
                if (branch != null) {
                    cloneCmd.add("-b");
                    cloneCmd.add(branch);
                }
                cloneCmd.add(repoUrl);
                cloneCmd.add(name);
                int cloneResult = runCommandSilent(workPath, logFile, cloneCmd.toArray(new String[0]));
                if (cloneResult != 0) {
                    return new TestResult(name, type, false, "Failed to clone repository", elapsed(startTime), logFile);
                }
            } else {
                if (!silent) System.out.println("  " + DIM + "🔄 Updating repository..." + RESET);
                // Discard any local changes (e.g., from versions plugin)
                runCommandSilent(projectPath, logFile, "git", "checkout", "--", ".");
                // Fetch the specific branch or default
                String targetBranch = branch != null ? branch : getDefaultBranch(projectPath, logFile);
                int fetchResult = runCommandSilent(projectPath, logFile, "git", "fetch", "--depth", "1", "origin", targetBranch);
                int checkoutResult = runCommandSilent(projectPath, logFile, "git", "checkout", targetBranch);

                // If branch doesn't exist locally (e.g., config changed), re-clone
                if (fetchResult != 0 || checkoutResult != 0) {
                    if (!silent) System.out.println("  " + DIM + "🔄 Branch mismatch, re-cloning..." + RESET);
                    deleteDirectory(projectPath);
                    List<String> cloneCmd = new ArrayList<>(List.of("git", "clone", "--depth", "1", "--single-branch"));
                    if (branch != null) {
                        cloneCmd.add("-b");
                        cloneCmd.add(branch);
                    }
                    cloneCmd.add(repoUrl);
                    cloneCmd.add(name);
                    int cloneResult = runCommandSilent(workPath, logFile, cloneCmd.toArray(new String[0]));
                    if (cloneResult != 0) {
                        return new TestResult(name, type, false, "Failed to clone repository", elapsed(startTime), logFile);
                    }
                } else {
                    runCommandSilent(projectPath, logFile, "git", "reset", "--hard", "origin/" + targetBranch);
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
            if (!silent) System.out.println("  " + DIM + "$ mvn " + String.join(" ", setPropertyArgs) + RESET);
            runMavenSilent(buildPath, logFile, javaVersion, setPropertyArgs);

            // Also try versions:set for direct vaadin-bom references
            List<String> setVersionArgs = new ArrayList<>();
            setVersionArgs.add("versions:set");
            setVersionArgs.add("-DnewVersion=" + vaadinVersion);
            setVersionArgs.add("-DartifactId=vaadin-bom");
            setVersionArgs.add("-DgenerateBackupPoms=false");
            setVersionArgs.addAll(getCommonMvnArgs());
            if (!silent) System.out.println("  " + DIM + "$ mvn " + String.join(" ", setVersionArgs) + RESET);
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
            if (!silent) System.out.println("  " + DIM + "$ mvn " + String.join(" ", mvnArgs) + RESET);

            // Use silent build for concurrent execution (output goes to log file, displayed via printLogTail)
            int buildResult = silent
                    ? runMavenSilent(buildPath, logFile, javaVersion, mvnArgs)
                    : runMavenWithTail(buildPath, logFile, javaVersion, mvnArgs);

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

    /**
     * Detect the project's configured Vaadin version from pom.xml.
     * Uses Maven help:evaluate to get the vaadin.version property.
     * @return The version string, or null if not found
     */
    private String detectProjectVaadinVersion(Path buildPath, String javaVersion) {
        try {
            // Use help:evaluate to get the vaadin.version property
            List<String> args = List.of(
                    "help:evaluate",
                    "-Dexpression=vaadin.version",
                    "-q",
                    "-DforceStdout"
            );

            Path tempLog = Files.createTempFile("vaadin-version", ".log");
            int result = runMavenSilent(buildPath, tempLog, javaVersion, new ArrayList<>(args));

            if (result == 0) {
                String output = Files.readString(tempLog).trim();
                // Filter out Maven noise - version should match pattern like 24.6.0 or 25.0-SNAPSHOT
                if (output.matches("\\d+\\.\\d+.*")) {
                    Files.deleteIfExists(tempLog);
                    return output;
                }
            }
            Files.deleteIfExists(tempLog);
        } catch (Exception e) {
            // Ignore - version detection is optional
        }
        return null;
    }

    /**
     * Run a verification build with the project's original Vaadin version.
     * Used to determine if a failure is specific to the new version or a pre-existing issue.
     * @return true if build succeeds, false otherwise
     */
    private boolean verifyWithOriginalVersion(Path buildPath, String javaVersion, boolean useAddonsRepo,
                                               List<String> extraMvnArgs, Path logFile) {
        try {
            // Reset pom.xml to original state
            runCommandSilent(buildPath, logFile, "git", "checkout", "--", "pom.xml");

            // Run build without version modification
            List<String> mvnArgs = new ArrayList<>();
            mvnArgs.add("clean");
            mvnArgs.add("verify");
            mvnArgs.addAll(getCommonMvnArgs());
            if (useAddonsRepo) {
                mvnArgs.add("-Pvaadin-addons");
            }
            mvnArgs.addAll(extraMvnArgs);

            int buildResult = runMavenSilent(buildPath, logFile, javaVersion, mvnArgs);
            return buildResult == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if there's an open GitHub issue for this project and version.
     * Uses GitHub REST API (no authentication needed for public repos).
     * @return The issue URL if found, null otherwise
     */
    private String findOpenGitHubIssue(String projectName, String version) {
        try {
            // Use GitHub REST API to search for open issues
            // Search in repo for issues containing project name and version
            String searchQuery = java.net.URLEncoder.encode(
                    "repo:mstahv/vaadin-ecosystem-build is:issue is:open " + projectName + " " + version,
                    java.nio.charset.StandardCharsets.UTF_8);
            String url = "https://api.github.com/search/issues?q=" + searchQuery + "&per_page=1";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // Check if there are results: "total_count":1 or more
                var countMatch = Pattern.compile("\"total_count\"\\s*:\\s*(\\d+)").matcher(body);
                if (countMatch.find() && Integer.parseInt(countMatch.group(1)) > 0) {
                    // Extract the HTML URL of the first issue
                    var urlMatch = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+/issues/\\d+)\"").matcher(body);
                    if (urlMatch.find()) {
                        return urlMatch.group(1);
                    }
                }
            }
        } catch (Exception e) {
            // API error - assume no known issue
        }
        return null;
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

    private void printFinalSummary(List<TestResult> results, long totalTimeMs) {
        System.out.println("-".repeat(60));
        System.out.println("📁 Build logs saved to: " + versionOutputPath + "/");
        System.out.println();

        int passed = 0, failed = 0, knownIssues = 0, ignored = 0;
        for (TestResult result : results) {
            if (result.message().startsWith("Ignored:")) {
                ignored++;
            } else if (result.success()) {
                passed++;
            } else {
                // Check if this is a known issue
                BuildStatus status = statusMap.get(result.projectName());
                if (status == BuildStatus.KNOWN_ISSUE) {
                    knownIssues++;
                } else {
                    failed++;
                }
            }
        }

        String totalTimeStr = formatDuration(totalTimeMs);
        String summaryIcon = failed == 0 ? "🎉" : "💔";
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("%s Total: %d | %s✅ Passed: %d%s",
                summaryIcon, results.size(), GREEN, passed, RESET));
        if (knownIssues > 0) {
            summary.append(String.format(" | %s⚠️  Known issues: %d%s", YELLOW, knownIssues, RESET));
        }
        summary.append(String.format(" | %s❌ Failed: %d%s | ⏭️  Ignored: %d",
                failed > 0 ? RED : "", failed, failed > 0 ? RESET : "", ignored));
        System.out.println(summary);
        System.out.println("⏱️  Total time: " + totalTimeStr);
        System.out.println("=".repeat(60));

        // Write markdown report
        writeMarkdownReport(results, passed, failed, knownIssues, ignored, totalTimeMs);
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return String.format("%.1fs", ms / 1000.0);
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) {
            return String.format("%dm %ds", minutes, seconds);
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    private void writeMarkdownReport(List<TestResult> results, int passed, int failed, int knownIssues, int ignored, long totalTimeMs) {
        Path reportPath = versionOutputPath.resolve("results.md");
        try (BufferedWriter writer = Files.newBufferedWriter(reportPath)) {
            String status;
            if (failed == 0 && knownIssues == 0) {
                status = "🎉 All tests passed";
            } else if (failed == 0) {
                status = "⚠️ All tests passed (with known issues)";
            } else {
                status = "💔 Some tests failed";
            }
            String timestamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " UTC";

            writer.write("# Vaadin Ecosystem Build Report\n\n");
            writer.write("**Vaadin Version:** `" + vaadinVersion + "`\n");
            writer.write("**Last Run:** " + timestamp + "\n");
            writer.write("**Total Time:** " + formatDuration(totalTimeMs) + "\n");
            writer.write("**Status:** " + status + "\n\n");

            // Group by type
            var smokeTestResults = results.stream().filter(r -> r.type() == ProjectType.SMOKE_TEST).toList();
            var addonResults = results.stream().filter(r -> r.type() == ProjectType.ADDON).toList();
            var appResults = results.stream().filter(r -> r.type() == ProjectType.APP).toList();

            if (!smokeTestResults.isEmpty()) {
                writer.write("## 🔥 Smoke Test\n\n");
                writer.write("| Project | Status | Duration |\n");
                writer.write("|---------|--------|----------|\n");
                for (TestResult result : smokeTestResults) {
                    writeResultRow(writer, result);
                }
                writer.write("\n");
            }

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
            StringBuilder summary = new StringBuilder();
            summary.append("**Summary:** ").append(results.size()).append(" total");
            summary.append(" | ✅ ").append(passed).append(" passed");
            if (knownIssues > 0) {
                summary.append(" | ⚠️ ").append(knownIssues).append(" known issues");
            }
            summary.append(" | ❌ ").append(failed).append(" failed");
            summary.append(" | ⏭️ ").append(ignored).append(" ignored\n");
            writer.write(summary.toString());

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
            // Check if this is a known issue
            BuildStatus status = statusMap.get(result.projectName());
            if (status == BuildStatus.KNOWN_ISSUE) {
                String issueUrl = knownIssueUrls.get(result.projectName());
                if (issueUrl != null) {
                    statusEmoji = "[⚠️ KNOWN ISSUE](" + issueUrl + ")";
                } else {
                    statusEmoji = "⚠️ KNOWN ISSUE";
                }
            } else {
                statusEmoji = "❌ FAILED";
            }
        }
        String duration = result.durationMs() > 0
                ? String.format("%.1fs", result.durationMs() / 1000.0)
                : "-";
        writer.write("| " + result.projectName() + " | " + statusEmoji + " | " + duration + " |\n");
    }

    private void writeFailedProjectsList() {
        Path failedFile = versionOutputPath.resolve("failed-projects.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(failedFile)) {
            // Write Vaadin version as first line for CI to use
            writer.write("vaadin_version=" + vaadinVersion);
            writer.newLine();

            // Write each failed project (including known issues - they're still failing!)
            for (var entry : statusMap.entrySet()) {
                if (entry.getValue() == BuildStatus.FAILED || entry.getValue() == BuildStatus.KNOWN_ISSUE) {
                    writer.write(entry.getKey());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️  Warning: Could not write failed projects list: " + e.getMessage());
        }

        // Write failure metadata as JSON for CI to use in issue creation
        if (!failureMetadata.isEmpty()) {
            Path metadataFile = versionOutputPath.resolve("failure-metadata.json");
            try (BufferedWriter writer = Files.newBufferedWriter(metadataFile)) {
                writer.write("{\n");
                var entries = new ArrayList<>(failureMetadata.entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    String repoUrl = entry.getValue().repoUrl();
                    String originalVersion = entry.getValue().originalVersion();
                    boolean builds = entry.getValue().buildsWithOriginal();
                    List<String> notifyUsers = entry.getValue().notifyUsers();
                    writer.write("  \"" + entry.getKey() + "\": {\n");
                    writer.write("    \"repoUrl\": " + (repoUrl != null ? "\"" + repoUrl + "\"" : "null") + ",\n");
                    writer.write("    \"originalVersion\": " + (originalVersion != null ? "\"" + originalVersion + "\"" : "null") + ",\n");
                    writer.write("    \"buildsWithOriginal\": " + builds + ",\n");
                    writer.write("    \"notifyUsers\": [" + notifyUsers.stream().map(u -> "\"" + u + "\"").collect(java.util.stream.Collectors.joining(", ")) + "]\n");
                    writer.write("  }" + (i < entries.size() - 1 ? "," : "") + "\n");
                }
                writer.write("}\n");
            } catch (IOException e) {
                System.err.println("⚠️  Warning: Could not write failure metadata: " + e.getMessage());
            }
        }
    }

    // Version matching for version-specific configuration
    private boolean matchesVersion(String pattern, String version) {
        if (pattern == null || version == null) return false;

        // Exact match
        if (pattern.equals(version)) return true;

        // Wildcard match (25.* matches 25.0.0, 25.1-SNAPSHOT, etc.)
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return version.startsWith(prefix);
        }

        // Comparison operators (>=, <=, >, <)
        if (pattern.startsWith(">=")) {
            return compareVersions(version, pattern.substring(2)) >= 0;
        } else if (pattern.startsWith("<=")) {
            return compareVersions(version, pattern.substring(2)) <= 0;
        } else if (pattern.startsWith(">")) {
            return compareVersions(version, pattern.substring(1)) > 0;
        } else if (pattern.startsWith("<")) {
            return compareVersions(version, pattern.substring(1)) < 0;
        }

        return false;
    }

    // Compare two version strings (major.minor comparison)
    // Returns negative if v1 < v2, 0 if equal, positive if v1 > v2
    private int compareVersions(String v1, String v2) {
        // Extract major.minor from version strings (e.g., "25.0-SNAPSHOT" -> [25, 0])
        int[] parts1 = extractMajorMinor(v1);
        int[] parts2 = extractMajorMinor(v2);

        if (parts1[0] != parts2[0]) {
            return parts1[0] - parts2[0];
        }
        return parts1[1] - parts2[1];
    }

    private int[] extractMajorMinor(String version) {
        // Remove -SNAPSHOT, -beta, etc. suffix for comparison
        String clean = version.replaceAll("-.*", "");
        String[] parts = clean.split("\\.");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return new int[] { major, minor };
    }

    // Find matching VersionConfig for a project and Vaadin version
    // Checks project-specific overrides first, then falls back to DEFAULT_VERSION_OVERRIDES
    private VersionConfig findVersionConfig(Map<String, VersionConfig> overrides, String vaadinVersion) {
        // First check project-specific overrides
        if (overrides != null && !overrides.isEmpty()) {
            for (var entry : overrides.entrySet()) {
                if (matchesVersion(entry.getKey(), vaadinVersion)) {
                    return entry.getValue();  // Return even if empty (allows opt-out of defaults)
                }
            }
        }

        // Fall back to default version overrides
        for (var entry : DEFAULT_VERSION_OVERRIDES.entrySet()) {
            if (matchesVersion(entry.getKey(), vaadinVersion)) {
                return entry.getValue();
            }
        }

        return null;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new EcosystemBuild()).execute(args);
        System.exit(exitCode);
    }
}
