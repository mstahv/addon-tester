///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "addon-tester", mixinStandardHelpOptions = true, version = "1.0",
        description = "Tests Vaadin add-ons against framework version changes")
public class AddonTester implements Callable<Integer> {

    @Option(names = {"--vaadin.version", "-v"}, description = "Vaadin version to test against", defaultValue = "25.0.5")
    private String vaadinVersion;

    @Option(names = {"--work-dir", "-w"}, description = "Working directory for cloning projects", defaultValue = "work")
    private String workDir;

    @Option(names = {"--clean", "-c"}, description = "Clean work directory before running")
    private boolean clean;

    // Add-on configurations
    record AddonConfig(
            String name,
            String repoUrl,
            String branch,
            List<String> extraMvnArgs,
            boolean ignored,
            String ignoreReason
    ) {
        AddonConfig(String name, String repoUrl) {
            this(name, repoUrl, null, List.of(), false, null);
        }
    }

    // Test result
    record TestResult(String addonName, boolean success, String message, long durationMs) {}

    // Configure add-ons to test here
    private static final List<AddonConfig> ADDONS = List.of(
            new AddonConfig("hugerte-for-flow", "https://github.com/parttio/hugerte-for-flow")
    );

    @Override
    public Integer call() throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Vaadin Add-on Compatibility Tester");
        System.out.println("=".repeat(60));
        System.out.println("Testing against Vaadin version: " + vaadinVersion);
        System.out.println();

        Path workPath = Path.of(workDir);

        if (clean && Files.exists(workPath)) {
            System.out.println("Cleaning work directory...");
            deleteDirectory(workPath);
        }

        Files.createDirectories(workPath);

        List<TestResult> results = new ArrayList<>();

        for (AddonConfig addon : ADDONS) {
            if (addon.ignored()) {
                System.out.println("\n" + "-".repeat(60));
                System.out.println("SKIPPING: " + addon.name() + " (ignored)");
                System.out.println("Reason: " + addon.ignoreReason());
                results.add(new TestResult(addon.name(), false, "Ignored: " + addon.ignoreReason(), 0));
                continue;
            }

            System.out.println("\n" + "-".repeat(60));
            System.out.println("Testing: " + addon.name());
            System.out.println("-".repeat(60));

            long startTime = System.currentTimeMillis();
            TestResult result = testAddon(addon, workPath);
            results.add(result);
        }

        // Print summary
        printSummary(results);

        // Return non-zero if any test failed
        boolean allPassed = results.stream().allMatch(TestResult::success);
        return allPassed ? 0 : 1;
    }

    private TestResult testAddon(AddonConfig addon, Path workPath) {
        long startTime = System.currentTimeMillis();
        Path addonPath = workPath.resolve(addon.name());

        try {
            // Clone or update repository
            if (!Files.exists(addonPath)) {
                System.out.println("Cloning " + addon.repoUrl() + "...");
                int cloneResult = runCommand(workPath, "git", "clone", addon.repoUrl(), addon.name());
                if (cloneResult != 0) {
                    return new TestResult(addon.name(), false, "Failed to clone repository", elapsed(startTime));
                }
            } else {
                System.out.println("Updating existing repository...");
                runCommand(addonPath, "git", "fetch", "--all");
                runCommand(addonPath, "git", "reset", "--hard", "origin/" + (addon.branch() != null ? addon.branch() : "main"));
            }

            // Checkout specific branch if configured
            if (addon.branch() != null) {
                System.out.println("Checking out branch: " + addon.branch());
                int checkoutResult = runCommand(addonPath, "git", "checkout", addon.branch());
                if (checkoutResult != 0) {
                    // Try with origin/ prefix
                    checkoutResult = runCommand(addonPath, "git", "checkout", "-b", addon.branch(), "origin/" + addon.branch());
                    if (checkoutResult != 0) {
                        return new TestResult(addon.name(), false, "Failed to checkout branch: " + addon.branch(), elapsed(startTime));
                    }
                }
            }

            // Build with specified Vaadin version
            System.out.println("Running mvn verify with Vaadin " + vaadinVersion + "...");
            List<String> mvnCommand = new ArrayList<>();
            mvnCommand.add("mvn");
            mvnCommand.add("verify");
            mvnCommand.add("-Dvaadin.version=" + vaadinVersion);
            mvnCommand.add("-B"); // Batch mode for cleaner output
            mvnCommand.addAll(addon.extraMvnArgs());

            int buildResult = runCommand(addonPath, mvnCommand.toArray(new String[0]));

            if (buildResult == 0) {
                return new TestResult(addon.name(), true, "Build successful", elapsed(startTime));
            } else {
                return new TestResult(addon.name(), false, "Build failed (exit code: " + buildResult + ")", elapsed(startTime));
            }

        } catch (Exception e) {
            return new TestResult(addon.name(), false, "Error: " + e.getMessage(), elapsed(startTime));
        }
    }

    private int runCommand(Path workDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.inheritIO();
        Process process = pb.start();
        return process.waitFor();
    }

    private long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private void printSummary(List<TestResult> results) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Vaadin version tested: " + vaadinVersion);
        System.out.println();

        int passed = 0;
        int failed = 0;
        int ignored = 0;

        for (TestResult result : results) {
            String status;
            if (result.message().startsWith("Ignored:")) {
                status = "IGNORED";
                ignored++;
            } else if (result.success()) {
                status = "PASSED";
                passed++;
            } else {
                status = "FAILED";
                failed++;
            }

            String duration = result.durationMs() > 0 ? String.format(" (%.1fs)", result.durationMs() / 1000.0) : "";
            System.out.printf("  %-30s %s%s%n", result.addonName(), status, duration);
            if (!result.success() && !result.message().startsWith("Ignored:")) {
                System.out.printf("    -> %s%n", result.message());
            }
        }

        System.out.println();
        System.out.println("-".repeat(60));
        System.out.printf("Total: %d | Passed: %d | Failed: %d | Ignored: %d%n",
                results.size(), passed, failed, ignored);
        System.out.println("=".repeat(60));
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new AddonTester()).execute(args);
        System.exit(exitCode);
    }
}
