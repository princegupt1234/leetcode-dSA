import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReadmeGenerator {
    private static final String START_MARKER = "<!-- README-GENERATED-START -->";
    private static final String END_MARKER = "<!-- README-GENERATED-END -->";
    private static final Pattern PROBLEM_DIRECTORY = Pattern.compile("^(\\d+)[-_](.+)$");
    private static final Pattern HTML_TITLE = Pattern.compile(
            "(?is)<h2\\b[^>]*>.*?<a\\b[^>]*>(.*?)</a>.*?</h2>");
    private static final Pattern MARKDOWN_TITLE = Pattern.compile(
            "(?im)^#\\s+(?:\\d+\\.\\s*)?(.+?)\\s*$");
    private static final Pattern HTML_DIFFICULTY = Pattern.compile(
            "(?is)<h3\\b[^>]*>\\s*(Easy|Medium|Hard)\\s*</h3>");
    private static final Pattern BADGE_DIFFICULTY = Pattern.compile(
            "(?i)alt\\s*=\\s*['\"]Difficulty:\\s*(Easy|Medium|Hard)['\"]");
    private static final Pattern MARKDOWN_DIFFICULTY = Pattern.compile(
            "(?im)^\\s*Difficulty\\s*:\\s*(Easy|Medium|Hard)\\s*$");
    private static final Pattern TOPICS = Pattern.compile(
            "(?im)^\\s*(?:Topics?|Tags?)\\s*:\\s*(.+?)\\s*$");
    private static final Pattern LEETCODE_PROBLEM_PATH =
            Pattern.compile("^/problems/[a-z0-9-]+/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HARNESS_FILE =
            Pattern.compile("(?i:^(?:test|tests|harness|testharness)\\.java$)|.*(?:Test|Tests|Harness)\\.java$");
    private static final Map<String, String> DIFFICULTY_COLORS = Map.of(
            "Easy", "16A34A",
            "Medium", "D97706",
            "Hard", "DC2626");
    private static final Map<Integer, List<String>> KNOWN_TOPICS = Map.ofEntries(
            Map.entry(1, List.of("Array", "Hash Table")),
            Map.entry(4, List.of("Array", "Binary Search", "Divide and Conquer")),
            Map.entry(7, List.of("Math")),
            Map.entry(8, List.of("String")),
            Map.entry(9, List.of("Math")),
            Map.entry(13, List.of("Hash Table", "Math", "String")),
            Map.entry(21, List.of("Linked List", "Recursion")),
            Map.entry(26, List.of("Array", "Two Pointers")),
            Map.entry(258, List.of("Math", "Number Theory")),
            Map.entry(344, List.of("String", "Two Pointers")));

    private ReadmeGenerator() {
    }

    public static void main(String[] args) {
        try {
            Path root = findRepositoryRoot(Path.of("").toAbsolutePath().normalize());
            List<Problem> problems = discoverProblems(root);
            if (problems.isEmpty()) {
                throw new IllegalStateException("No Java LeetCode solutions were found under " + root);
            }

            List<Problem> recent = new ArrayList<>(problems);
            boolean gitAvailable = isGitRepository(root);
            if (gitAvailable) {
                for (Problem problem : recent) {
                    problem.lastChanged = lastChanged(root, problem.solution);
                }
                recent.sort(Comparator
                        .comparing((Problem problem) -> problem.lastChanged,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((Problem problem) -> problem.number, Comparator.reverseOrder()));
            } else {
                System.err.println("Git history is unavailable; recent solution dates cannot be determined.");
                recent.clear();
            }

            Path readme = root.resolve("README.md");
            String generated = generate(problems, recent);
            String output = mergeGeneratedSection(readme, generated);
            if (!Files.exists(readme) || !Files.readString(readme, StandardCharsets.UTF_8).equals(output)) {
                Files.writeString(readme, output, StandardCharsets.UTF_8);
                System.out.println("Generated README.md for " + problems.size() + " solutions.");
            } else {
                System.out.println("README.md is already up to date.");
            }
        } catch (IOException | IllegalStateException exception) {
            System.err.println("README generation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Path findRepositoryRoot(Path start) throws IOException {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"))
                    || Files.isDirectory(directory.resolve(".github").resolve("workflows"))) {
                return directory;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + start);
    }

    private static List<Problem> discoverProblems(Path root) throws IOException {
        Map<Path, List<Path>> solutionsByDirectory = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return !directory.equals(root) && isExcluded(root.relativize(directory))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()
                        && file.toString().endsWith(".java")
                        && !HARNESS_FILE.matcher(file.getFileName().toString()).matches()) {
                    Path directory = problemDirectory(root, file.getParent());
                    if (directory != null) {
                        solutionsByDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Map<Integer, Problem> problemsByNumber = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Path>> entry : solutionsByDirectory.entrySet()) {
            Path directory = entry.getKey();
            Matcher directoryMatcher = PROBLEM_DIRECTORY.matcher(directory.getFileName().toString());
            if (!directoryMatcher.matches()) {
                continue;
            }

            int number;
            try {
                number = Integer.parseInt(directoryMatcher.group(1));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Invalid problem number in directory " + directory, exception);
            }

            Path solution = chooseSolution(directory, directoryMatcher.group(2), entry.getValue());
            Path relativeSolution = root.relativize(solution).normalize();
            if (relativeSolution.isAbsolute() || relativeSolution.startsWith("..")
                    || !Files.isRegularFile(root.resolve(relativeSolution))) {
                throw new IllegalStateException("Invalid Java solution path: " + relativeSolution);
            }

            Problem problem = readProblemMetadata(directory, number, directoryMatcher.group(2), relativeSolution);
            Problem existing = problemsByNumber.putIfAbsent(number, problem);
            if (existing != null) {
                throw new IllegalStateException("Multiple solution directories use problem number " + number);
            }
        }

        List<Problem> problems = new ArrayList<>(problemsByNumber.values());
        problems.sort(Comparator.comparingInt(problem -> problem.number));
        return problems;
    }

    private static boolean isExcluded(Path relativePath) {
        for (Path part : relativePath) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".git") || name.equals(".github") || name.equals("tools")
                    || name.equals("test") || name.equals("tests") || name.equals("target")
                    || name.equals("build") || name.equals("out")) {
                return true;
            }
        }
        return false;
    }

    private static Path problemDirectory(Path root, Path directory) {
        for (Path current = directory; current != null && !current.equals(root); current = current.getParent()) {
            if (PROBLEM_DIRECTORY.matcher(current.getFileName().toString()).matches()) {
                return current;
            }
        }
        return null;
    }

    private static Path chooseSolution(Path directory, String titleSlug, List<Path> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String expectedName = titleSlug.replace('_', '-').toLowerCase(Locale.ROOT) + ".java";
        List<Path> matching = candidates.stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(expectedName))
                .toList();
        if (matching.size() == 1) {
            return matching.get(0);
        }

        candidates.sort(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)));
        throw new IllegalStateException("Expected one Java solution in " + directory + " but found: " + candidates);
    }

    private static Problem readProblemMetadata(
            Path directory, int number, String directoryTitle, Path solution) throws IOException {
        Path readme = directory.resolve("README.md");
        String metadata = Files.isRegularFile(readme)
                ? Files.readString(readme, StandardCharsets.UTF_8)
                : "";

        String title = extractTitle(metadata);
        if (title == null || title.isBlank()) {
            title = titleFromDirectory(directoryTitle);
        }

        String difficulty = extractDifficulty(metadata);
        String leetCodeUrl = extractLeetCodeUrl(metadata);
        List<String> topics = extractTopics(metadata);
        if (topics.isEmpty()) {
            topics = KNOWN_TOPICS.getOrDefault(number, List.of());
        }
        return new Problem(number, title, difficulty, leetCodeUrl, topics, solution);
    }

    private static String extractTitle(String metadata) {
        Matcher html = HTML_TITLE.matcher(metadata);
        if (html.find()) {
            String title = stripTags(html.group(1)).replaceFirst("^\\d+\\.\\s*", "").trim();
            return title.isBlank() ? null : title;
        }

        Matcher markdown = MARKDOWN_TITLE.matcher(metadata);
        return markdown.find() ? markdown.group(1).trim() : null;
    }

    private static String extractDifficulty(String metadata) {
        Matcher matcher = HTML_DIFFICULTY.matcher(metadata);
        if (!matcher.find()) {
            matcher = BADGE_DIFFICULTY.matcher(metadata);
        }
        if (!matcher.find(0)) {
            matcher = MARKDOWN_DIFFICULTY.matcher(metadata);
            if (!matcher.find()) {
                return null;
            }
        }
        String value = matcher.group(1);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String extractLeetCodeUrl(String metadata) {
        Matcher links = Pattern.compile("(?i)href\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(metadata);
        while (links.find()) {
            try {
                URI uri = URI.create(links.group(1));
                String host = uri.getHost();
                if (host != null
                        && (host.equalsIgnoreCase("leetcode.com") || host.equalsIgnoreCase("www.leetcode.com"))
                        && uri.getPath() != null
                        && LEETCODE_PROBLEM_PATH.matcher(uri.getPath()).matches()) {
                    return "https://leetcode.com" + uri.getPath().replaceFirst("/$", "");
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed links are not reliable metadata.
            }
        }
        return null;
    }

    private static List<String> extractTopics(String metadata) {
        Matcher matcher = TOPICS.matcher(metadata);
        if (!matcher.find()) {
            return List.of();
        }

        List<String> topics = new ArrayList<>();
        for (String topic : matcher.group(1).split("[,;|]")) {
            String cleaned = topic.trim();
            if (!cleaned.isBlank() && !cleaned.equalsIgnoreCase("Not recorded")) {
                topics.add(cleaned);
            }
        }
        return topics.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String titleFromDirectory(String directoryTitle) {
        String title = directoryTitle.replace('-', ' ').replace('_', ' ').trim();
        StringBuilder result = new StringBuilder();
        for (String word : title.split("\\s+")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String stripTags(String value) {
        return value.replaceAll("(?is)<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static boolean isGitRepository(Path root) throws IOException {
        ProcessResult result = runCommand(root, "git", "rev-parse", "--show-toplevel");
        if (result.exitCode != 0) {
            System.err.println("Git history is unavailable; recent solution dates cannot be determined.");
            return false;
        }
        return true;
    }

    private static Long lastChanged(Path root, Path solution) throws IOException {
        ProcessResult result = runCommand(root, "git", "log", "-1", "--format=%ct", "--",
                solution.toString().replace('\\', '/'));
        if (result.exitCode != 0) {
            throw new IllegalStateException("Could not read Git history for " + solution + ": " + result.output);
        }
        String timestamp = result.output.trim();
        if (timestamp.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Git timestamp for " + solution + ": " + timestamp, exception);
        }
    }

    private static ProcessResult runCommand(Path directory, String... command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        } catch (IOException exception) {
            if (Objects.equals(command[0], "git")) {
                return new ProcessResult(127, exception.getMessage());
            }
            throw exception;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return new ProcessResult(process.waitFor(), output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + String.join(" ", command), exception);
        }
    }

    private static String generate(List<Problem> problems, List<Problem> recent) {
        long easy = problems.stream().filter(problem -> "Easy".equals(problem.difficulty)).count();
        long medium = problems.stream().filter(problem -> "Medium".equals(problem.difficulty)).count();
        long hard = problems.stream().filter(problem -> "Hard".equals(problem.difficulty)).count();

        StringBuilder readme = new StringBuilder();
        readme.append(START_MARKER).append("\n\n");
        readme.append("# LeetCode DSA Solutions\n\n");
        readme.append("Java solutions to LeetCode problems, automatically synced using LeetHub v2.\n\n");
        readme.append("![Java](https://img.shields.io/badge/Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ")
                .append("![LeetHub v2](https://img.shields.io/badge/LeetHub-v2-6E40C9?style=flat-square) ")
                .append("![LeetCode](https://img.shields.io/badge/LeetCode-Problem%20Solving-FFA116?style=flat-square&logo=leetcode&logoColor=white)\n\n");

        readme.append("## 📊 Progress Summary\n\n");
        readme.append("<table><tr>")
                .append(statCell("Total Solved", problems.size(), "2563EB"))
                .append(statCell("Easy", easy, "16A34A"))
                .append(statCell("Medium", medium, "D97706"))
                .append(statCell("Hard", hard, "DC2626"))
                .append("</tr></table>\n\n");

        readme.append("## 🕒 Recently Added Solutions\n\n");
        readme.append("| # | Problem | Difficulty | Solution |\n")
                .append("| ---: | --- | :---: | :---: |\n");
        if (recent.isEmpty()) {
            readme.append("| — | No Git history available | Not recorded | — |\n\n");
        } else {
            recent.stream().limit(5).forEach(problem -> appendProblemRow(readme, problem, false));
            readme.append('\n');
        }

        readme.append("## 📚 All Problems and Solutions\n\n");
        readme.append("| # | Problem | Difficulty | Topic | Java Solution |\n")
                .append("| ---: | --- | :---: | --- | :---: |\n");
        for (Problem problem : problems) {
            appendProblemRow(readme, problem, true);
        }
        readme.append('\n');

        appendTopics(readme, problems);
        appendRunGuide(readme);
        readme.append("\n---\n\n");
        readme.append("If the workflow cannot push its generated README, enable **Read and write permissions** ")
                .append("under **Settings → Actions → General → Workflow permissions**.\n\n");
        readme.append("With JDK 17+, regenerate locally using `javac -encoding UTF-8 tools/ReadmeGenerator.java` ")
                .append("and `java -cp tools ReadmeGenerator`.\n\n");
        readme.append(END_MARKER).append('\n');
        return readme.toString();
    }

    private static String statCell(String label, long count, String color) {
        String badgeLabel = label.replace(' ', '_');
        return "<td align=\"center\"><img alt=\"" + label + ": " + count
                + "\" src=\"https://img.shields.io/badge/" + badgeLabel + "-" + count + "-" + color
                + "?style=for-the-badge\"></td>";
    }

    private static void appendProblemRow(StringBuilder readme, Problem problem, boolean includeTopic) {
        String number = String.format(Locale.ROOT, "%04d", problem.number);
        String title = escapeMarkdown(problem.title);
        String problemLink = problem.leetCodeUrl == null
                ? title
                : "[" + title + "](" + problem.leetCodeUrl + ")";
        String difficulty = problem.difficulty == null
                ? "Not recorded"
                : "![" + problem.difficulty + "](https://img.shields.io/badge/"
                        + problem.difficulty + "-" + DIFFICULTY_COLORS.get(problem.difficulty)
                        + "?style=flat-square)";
        String solutionLink = "[View Code](./" + markdownPath(problem.solution) + ")";

        readme.append("| ").append(number).append(" | ").append(problemLink).append(" | ")
                .append(difficulty).append(" | ");
        if (includeTopic) {
            readme.append(problem.topics.isEmpty()
                    ? "Not recorded"
                    : String.join(", ", problem.topics.stream().map(topic -> "`" + topic + "`").toList()))
                    .append(" | ").append(solutionLink).append(" |\n");
        } else {
            readme.append(solutionLink).append(" |\n");
        }
    }

    private static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    private static String markdownPath(Path path) {
        return path.toString().replace('\\', '/').replace(" ", "%20");
    }

    private static void appendTopics(StringBuilder readme, List<Problem> problems) {
        Map<String, List<Problem>> byTopic = new LinkedHashMap<>();
        problems.stream()
                .flatMap(problem -> problem.topics.stream().map(topic -> Map.entry(topic, problem)))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> byTopic.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue()));

        readme.append("## 📂 Topic-wise Navigation\n\n");
        if (byTopic.isEmpty()) {
            readme.append("Topic metadata is not recorded for these solutions yet.\n\n");
            return;
        }

        readme.append("<table><tr>");
        for (Map.Entry<String, List<Problem>> entry : byTopic.entrySet()) {
            Problem firstProblem = entry.getValue().get(0);
            String topic = escapeHtml(entry.getKey());
            readme.append("<td align=\"center\"><a href=\"./")
                    .append(markdownPath(firstProblem.solution.getParent()))
                    .append("/\">").append(topic).append("</a><br><sub>(")
                    .append(entry.getValue().stream().map(problem -> problem.number).distinct().count())
                    .append(")</sub></td>");
        }
        readme.append("</tr></table>\n\n");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void appendRunGuide(StringBuilder readme) {
        readme.append("## ▶️ How to Run a Solution\n\n");
        readme.append("<table><tr><td valign=\"top\">\n\n");
        readme.append("1. Navigate to a problem folder in this repository.\n")
                .append("2. Open its Java solution file.\n")
                .append("3. LeetCode solutions commonly contain a `Solution` class without a `main()` method.\n")
                .append("4. Create a separate test harness with `main()` for local execution; do not change the submission.\n\n");
        readme.append("</td><td valign=\"top\">\n\n");
        readme.append("**Java example**\n\n")
                .append("```java\n")
                .append("import java.util.Arrays;\n\n")
                .append("class TwoSumTest {\n")
                .append("    public static void main(String[] args) {\n")
                .append("        Solution solution = new Solution();\n")
                .append("        int[] result = solution.twoSum(new int[] {2, 7, 11, 15}, 9);\n")
                .append("        System.out.println(Arrays.toString(result));\n")
                .append("    }\n")
                .append("}\n")
                .append("```\n\n");
        readme.append("</td></tr></table>\n");
    }

    private static String mergeGeneratedSection(Path readme, String generated) throws IOException {
        if (!Files.exists(readme)) {
            return generated;
        }
        String current = Files.readString(readme, StandardCharsets.UTF_8);
        int start = current.indexOf(START_MARKER);
        int end = current.indexOf(END_MARKER);
        if (start < 0 && end < 0) {
            throw new IllegalStateException(
                    "README.md exists without generated-section markers; refusing to overwrite manual content.");
        }
        if (start < 0 || end < start || current.indexOf(START_MARKER, start + START_MARKER.length()) >= 0
                || current.indexOf(END_MARKER, end + END_MARKER.length()) >= 0) {
            throw new IllegalStateException("README.md has malformed generated-section markers.");
        }
        String prefix = current.substring(0, start);
        String suffix = current.substring(end + END_MARKER.length());
        if (suffix.isBlank()) {
            suffix = "";
        } else if (generated.endsWith("\n") && suffix.startsWith("\n")) {
            suffix = suffix.substring(1);
        }
        return prefix + generated + suffix;
    }

    private static final class Problem {
        private final int number;
        private final String title;
        private final String difficulty;
        private final String leetCodeUrl;
        private final List<String> topics;
        private final Path solution;
        private Long lastChanged;

        private Problem(
                int number,
                String title,
                String difficulty,
                String leetCodeUrl,
                List<String> topics,
                Path solution) {
            this.number = number;
            this.title = title;
            this.difficulty = difficulty;
            this.leetCodeUrl = leetCodeUrl;
            this.topics = topics;
            this.solution = solution;
        }
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
