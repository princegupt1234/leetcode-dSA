import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class UpdateReadme {
    private static final Path ROOT = Path.of(".");
    private static final Path README = ROOT.resolve("README.md");
    private static final Path CATALOG = ROOT.resolve("PROBLEMS.md");
    private static final String TOPICS_START = "<!---LeetCode Topics Start-->";
    private static final String TOPICS_END = "<!---LeetCode Topics End-->";
    private static final Pattern FOLDER_NAME = Pattern.compile("^(\\d+)-(.+)$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern TITLE_HEADING = Pattern.compile("(?is)<h2\\b[^>]*>(.*?)</h2>");
    private static final Pattern DIFFICULTY = Pattern.compile("(?i)<h3>\\s*(Easy|Medium|Hard)\\s*</h3>|Difficulty[-:]\\s*(Easy|Medium|Hard)");

    public static void main(String[] args) throws Exception {
        String existingReadme = Files.exists(README) ? Files.readString(README) : "";
        String existingCatalog = Files.exists(CATALOG) ? Files.readString(CATALOG) : "";
        Map<String, Set<String>> topicsByFolder = parseLeetHubTopics(existingReadme);
        if (topicsByFolder.isEmpty()) topicsByFolder = parseCatalogTopics(existingCatalog);

        List<Problem> problems = scanProblems(topicsByFolder);
        Files.writeString(README, buildReadme(problems));
        Files.writeString(CATALOG, buildCatalog(problems));

        long easy = countDifficulty(problems, "Easy");
        long medium = countDifficulty(problems, "Medium");
        long hard = countDifficulty(problems, "Hard");
        System.out.printf("Generated documentation for %d problems (Easy: %d, Medium: %d, Hard: %d).%n",
            problems.size(), easy, medium, hard);
    }

    private static List<Problem> scanProblems(Map<String, Set<String>> topicsByFolder) throws Exception {
        List<Path> folders;
        try (Stream<Path> entries = Files.list(ROOT)) {
            folders = entries.filter(Files::isDirectory)
                .filter(path -> FOLDER_NAME.matcher(path.getFileName().toString()).matches())
                .sorted(Comparator.comparingInt(path -> getNumber(path.getFileName().toString())))
                .toList();
        }

        List<Problem> problems = new ArrayList<>();
        Set<Integer> problemNumbers = new HashSet<>();
        Map<String, String> statsDifficulties = parseStatsDifficulty();

        for (Path folder : folders) {
            String folderName = folder.getFileName().toString();
            int number = getNumber(folderName);
            if (!problemNumbers.add(number)) {
                throw new IllegalStateException("Duplicate problem number in solution folders: " + number);
            }

            List<Path> javaFiles;
            try (Stream<Path> entries = Files.list(folder)) {
                javaFiles = entries.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted().toList();
            }
            if (javaFiles.isEmpty()) {
                throw new IllegalStateException("No Java solution found in " + folderName);
            }

            Path problemReadme = folder.resolve("README.md");
            String metadata = Files.exists(problemReadme) ? Files.readString(problemReadme) : "";
            String title = parseTitle(metadata);
            String difficulty = parseDifficulty(metadata);
            if ("Title not recorded".equals(title) || "Not recorded".equals(difficulty)) {
                String statsKey = findStatsKey(statsDifficulties, folderName);
                if ("Not recorded".equals(difficulty) && statsKey != null) {
                    difficulty = statsDifficulties.get(statsKey);
                }
            }

            List<String> topics = new ArrayList<>(topicsByFolder.getOrDefault(folderName, Set.of()));
            topics.sort(String.CASE_INSENSITIVE_ORDER);
            problems.add(new Problem(number, title, difficulty, topics, javaFiles.get(0)));
        }
        return problems;
    }

    private static String parseTitle(String metadata) {
        Matcher matcher = TITLE_HEADING.matcher(metadata);
        if (!matcher.find()) return "Title not recorded";
        String title = matcher.group(1).replaceAll("(?s)<[^>]*>", "").trim();
        title = decodeHtml(title).replaceFirst("^\\d+\\.\\s*", "").trim();
        return title.isEmpty() ? "Title not recorded" : title;
    }

    private static String parseDifficulty(String metadata) {
        Matcher matcher = DIFFICULTY.matcher(metadata);
        if (!matcher.find()) return "Not recorded";
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return capitalize(value);
    }

    private static String decodeHtml(String value) {
        return value.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static Map<String, Set<String>> parseLeetHubTopics(String readme) {
        Map<String, Set<String>> topics = new HashMap<>();
        int start = readme.indexOf(TOPICS_START);
        int end = readme.indexOf(TOPICS_END);
        if (start < 0 || end < start) return topics;

        String currentTopic = null;
        for (String line : readme.substring(start + TOPICS_START.length(), end).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                currentTopic = trimmed.substring(3).trim();
                continue;
            }
            if (currentTopic == null) continue;

            Matcher linkMatcher = MARKDOWN_LINK.matcher(trimmed);
            while (linkMatcher.find()) {
                String folderName = folderFromLink(linkMatcher.group(1), linkMatcher.group(2));
                if (folderName != null) {
                    topics.computeIfAbsent(folderName, ignored -> new LinkedHashSet<>()).add(currentTopic);
                }
            }
        }
        return topics;
    }

    private static Map<String, Set<String>> parseCatalogTopics(String catalog) {
        Map<String, Set<String>> topics = new HashMap<>();
        for (String line : catalog.split("\\R")) {
            if (!line.startsWith("|")) continue;
            String[] columns = line.split("\\|", -1);
            if (columns.length < 5) continue;

            Matcher linkMatcher = MARKDOWN_LINK.matcher(columns[2]);
            if (!linkMatcher.find()) continue;
            String folderName = folderFromLink(linkMatcher.group(1), linkMatcher.group(2));
            if (folderName == null) continue;

            String topicColumn = columns[4].trim();
            if (topicColumn.equals("Not recorded") || topicColumn.isEmpty()) continue;
            Set<String> folderTopics = topics.computeIfAbsent(folderName, ignored -> new LinkedHashSet<>());
            for (String topic : topicColumn.split("\\s*;\\s*")) {
                if (!topic.isBlank() && !topic.equals("Not recorded")) folderTopics.add(topic);
            }
        }
        return topics;
    }

    private static String folderFromLink(String label, String target) {
        if (FOLDER_NAME.matcher(label).matches()) return label;
        String path = target.split("[?#]", 2)[0].replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String lastPart = path.substring(slash + 1);
        if (lastPart.endsWith(".java")) {
            int parentSlash = path.lastIndexOf('/', slash - 1);
            lastPart = path.substring(parentSlash + 1, slash);
        }
        return FOLDER_NAME.matcher(lastPart).matches() ? lastPart : null;
    }

    private static String buildReadme(List<Problem> problems) {
        long easy = countDifficulty(problems, "Easy");
        long medium = countDifficulty(problems, "Medium");
        long hard = countDifficulty(problems, "Hard");
        long unrecorded = countDifficulty(problems, "Not recorded");
        Map<String, Long> topicCounts = topicCounts(problems);

        StringBuilder readme = new StringBuilder();
        readme.append("# LeetCode DSA Solutions\n\n")
            .append("Java solutions synced from LeetCode using LeetHub v2.\n\n")
            .append("![Java](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk&logoColor=white)\n\n")
            .append("## Progress Dashboard\n\n")
            .append("| Total solved | Easy | Medium | Hard |\n")
            .append("| ---: | ---: | ---: | ---: |\n")
            .append("| ").append(problems.size()).append(" | ").append(easy).append(" | ")
            .append(medium).append(" | ").append(hard).append(" |\n\n");
        if (unrecorded > 0) {
            readme.append("Difficulty not recorded: ").append(unrecorded).append(".\n\n");
        }

        readme.append("## Topic Overview\n\n")
            .append("| Topic | Solved | Detailed index |\n")
            .append("| --- | ---: | --- |\n");
        topicCounts.forEach((topic, count) -> readme.append("| ").append(topic).append(" | ")
            .append(count).append(" | [Browse](PROBLEMS.md#topic-").append(topicAnchor(topic)).append(") |\n"));
        if (topicCounts.isEmpty()) {
            readme.append("| No topic metadata recorded | 0 | [Browse](PROBLEMS.md) |\n");
        }

        readme.append("\n## How to Run\n\n")
            .append("Solutions define LeetCode's `Solution` methods and do not include a `main` method. ")
            .append("Compile a solution with a local test harness, then run the harness:\n\n")
            .append("```bash\n")
            .append("javac -d out 0001-two-sum/0001-two-sum.java YourTestHarness.java\n")
            .append("java -cp out YourTestHarness\n")
            .append("```\n\n")
            .append("## Browse All Problems\n\n")
            .append("See the [complete problem catalog](PROBLEMS.md).\n\n")
            .append("Update documentation locally after a sync with `javac UpdateReadme.java` and `java UpdateReadme`.\n\n")
            .append(TOPICS_START).append('\n').append(TOPICS_END).append('\n');
        return readme.toString();
    }

    private static String buildCatalog(List<Problem> problems) {
        Map<String, Long> topicCounts = topicCounts(problems);
        StringBuilder catalog = new StringBuilder();
        catalog.append("# LeetCode Problem Catalog\n\n")
            .append("One row per solution folder. Titles and difficulty come from each problem README; topics are LeetHub v2 tags. Missing metadata is marked `Not recorded`.\n\n")
            .append("## Topic Overview\n\n")
            .append("| Topic | Solved |\n")
            .append("| --- | ---: |\n");
        topicCounts.forEach((topic, count) -> catalog.append("| <a id=\"topic-")
            .append(topicAnchor(topic)).append("\"></a>").append(topic).append(" | ")
            .append(count).append(" |\n"));

        catalog.append("\n## Problem Index\n\n")
            .append("| # | Problem | Difficulty | Topics |\n")
            .append("| ---: | --- | --- | --- |\n");
        for (Problem problem : problems) {
            String title = problem.title.replace("|", "\\|");
            String topicText = problem.topics.isEmpty() ? "Not recorded" : String.join("; ", problem.topics);
            catalog.append("| ").append(problem.number).append(" | [").append(title).append("](")
                .append(problem.javaFile.toString().replace('\\', '/')).append(") | ")
                .append(problem.difficulty).append(" | ").append(topicText).append(" |\n");
        }
        return catalog.toString();
    }

    private static Map<String, Long> topicCounts(List<Problem> problems) {
        Map<String, Long> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Problem problem : problems) {
            for (String topic : problem.topics) counts.merge(topic, 1L, Long::sum);
        }
        return counts;
    }

    private static long countDifficulty(List<Problem> problems, String difficulty) {
        return problems.stream().filter(problem -> problem.difficulty.equalsIgnoreCase(difficulty)).count();
    }

    private static String topicAnchor(String topic) {
        return topic.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static int getNumber(String folderName) {
        Matcher matcher = FOLDER_NAME.matcher(folderName);
        if (!matcher.matches()) throw new IllegalArgumentException("Unexpected solution folder: " + folderName);
        return Integer.parseInt(matcher.group(1));
    }

    private static String findStatsKey(Map<String, String> difficulties, String folderName) {
        if (difficulties.containsKey(folderName)) return folderName;
        Matcher matcher = FOLDER_NAME.matcher(folderName);
        if (!matcher.matches()) return null;
        String paddedFolder = String.format(Locale.ROOT, "%04d-%s", Integer.parseInt(matcher.group(1)), matcher.group(2));
        return difficulties.containsKey(paddedFolder) ? paddedFolder : null;
    }

    private static Map<String, String> parseStatsDifficulty() throws Exception {
        Map<String, String> difficulties = new HashMap<>();
        Path statsPath = ROOT.resolve("stats.json");
        if (!Files.exists(statsPath)) return difficulties;

        Pattern entryPattern = Pattern.compile("\"(\\d+-[^\"]+)\"\\s*:\\s*\\{[^{}]*?\"difficulty\"\\s*:\\s*\"(Easy|Medium|Hard)\"", Pattern.CASE_INSENSITIVE);
        Matcher matcher = entryPattern.matcher(Files.readString(statsPath));
        while (matcher.find()) difficulties.put(matcher.group(1), capitalize(matcher.group(2)));
        return difficulties;
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static final class Problem {
        private final int number;
        private final String title;
        private final String difficulty;
        private final List<String> topics;
        private final Path javaFile;

        private Problem(int number, String title, String difficulty, List<String> topics, Path javaFile) {
            this.number = number;
            this.title = title;
            this.difficulty = difficulty;
            this.topics = topics;
            this.javaFile = javaFile;
        }
    }
}
