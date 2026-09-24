import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class UpdateReadme {

    static final String TOPICS_START = "<!---LeetCode Topics Start-->";
    static final String TOPICS_END   = "<!---LeetCode Topics End-->";

    static final Map<String, String> KEYWORD_TOPICS = new LinkedHashMap<>();
    static {
        KEYWORD_TOPICS.put("palindrome",  "Math");
        KEYWORD_TOPICS.put("reverse",     "Math");
        KEYWORD_TOPICS.put("digit",       "Math");
        KEYWORD_TOPICS.put("add",         "Math");
        KEYWORD_TOPICS.put("math",        "Math");
        KEYWORD_TOPICS.put("string",      "String");
        KEYWORD_TOPICS.put("array",       "Array");
        KEYWORD_TOPICS.put("dynamic",     "Dynamic Programming");
        KEYWORD_TOPICS.put("tree",        "Tree");
        KEYWORD_TOPICS.put("graph",       "Graph");
        KEYWORD_TOPICS.put("binary",      "Binary Search");
        KEYWORD_TOPICS.put("hash",        "Hash Table");
        KEYWORD_TOPICS.put("linked",      "Linked List");
        KEYWORD_TOPICS.put("stack",       "Stack");
        KEYWORD_TOPICS.put("queue",       "Queue");
        KEYWORD_TOPICS.put("greedy",      "Greedy");
        KEYWORD_TOPICS.put("backtrack",   "Backtracking");
        KEYWORD_TOPICS.put("sort",        "Sorting");
        KEYWORD_TOPICS.put("sliding",     "Sliding Window");
        KEYWORD_TOPICS.put("two-pointer", "Two Pointers");
    }

    public static void main(String[] args) throws Exception {
        File root = new File(".");
        List<File> folders = new ArrayList<>();

        File[] entries = root.listFiles();
        if (entries == null) entries = new File[0];
        for (File f : entries) {
            if (f.isDirectory() && f.getName().matches("^\\d+.*") && !f.getName().startsWith(".")) {
                folders.add(f);
            }
        }
        folders.sort(Comparator.comparingInt(f -> getNumber(f.getName())));

        String existingReadme = Files.exists(Path.of("README.md"))
            ? Files.readString(Path.of("README.md")) : "";

        Map<String, String> statsDiffMap = parseStatsDifficulty();
        Map<String, List<String>> topicsFromReadme = parseTopicsFromReadme(existingReadme);

        StringBuilder rows = new StringBuilder();
        long easy = 0, medium = 0, hard = 0;

        for (File f : folders) {
            String name  = f.getName();
            int    num   = getNumber(name);
            String title = getTitle(name);
            String diff  = getDifficulty(f, statsDiffMap);
            String topic = getTopic(name, topicsFromReadme);
            String link  = "https://github.com/princegupt1234/leetcode-dSA/tree/main/" + name;

            if ("Easy".equalsIgnoreCase(diff)) easy++;
            else if ("Medium".equalsIgnoreCase(diff)) medium++;
            else if ("Hard".equalsIgnoreCase(diff)) hard++;

            rows.append(String.format("| %d | [%s](%s) | %s | %s |\n", num, title, link, diff, topic));
        }

        String mySection =
            "# 🧠 LeetCode DSA Solutions\n\n" +
            "A collection of LeetCode problems solved in **Java**, organized by problem number.\n\n" +
            "## 📁 Structure\n\n" +
            "Each folder is named as `<problem-number>-<problem-name>` and contains the Java solution.\n\n" +
            "## ✅ Problems Solved\n\n" +
            "| # | Problem | Difficulty | Topic |\n" +
            "|---|---------|------------|-------|\n" +
            rows + "\n" +
            "## 🚀 How to Run\n\n" +
            "```bash\n" +
            "javac Solution.java\n" +
            "java Solution\n" +
            "```\n\n" +
            "## 🛠️ Language\n\n" +
            "- Java\n\n" +
            "## 📈 Progress\n\n" +
            "![Solved](https://img.shields.io/badge/Solved-" + folders.size() + "-brightgreen) " +
            "![Easy](https://img.shields.io/badge/Easy-" + easy + "-green) " +
            "![Medium](https://img.shields.io/badge/Medium-" + medium + "-orange) " +
            "![Hard](https://img.shields.io/badge/Hard-" + hard + "-red)\n\n";

        String topicsSection = "";
        int start = existingReadme.indexOf(TOPICS_START);
        int end   = existingReadme.indexOf(TOPICS_END);
        if (start != -1 && end != -1) {
            topicsSection = existingReadme.substring(start, end + TOPICS_END.length()) + "\n";
        }

        Files.writeString(Path.of("README.md"), mySection + topicsSection);
        System.out.println("README updated with " + folders.size() + " problems (Easy: " + easy + ", Medium: " + medium + ", Hard: " + hard + ").");
    }

    static int getNumber(String name) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 9999;
    }

    static String getTitle(String name) {
        String[] words = name.replaceAll("^\\d+-", "").split("-");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    static String getDifficulty(File folder, Map<String, String> statsDiffMap) {
        String name = folder.getName();
        if (statsDiffMap.containsKey(name)) {
            return statsDiffMap.get(name);
        }

        int num = getNumber(name);
        String slug = name.replaceAll("^\\d+-", "");
        String paddedName = String.format("%04d-%s", num, slug);
        if (statsDiffMap.containsKey(paddedName)) {
            return statsDiffMap.get(paddedName);
        }
        String unpaddedName = num + "-" + slug;
        if (statsDiffMap.containsKey(unpaddedName)) {
            return statsDiffMap.get(unpaddedName);
        }

        File readme = new File(folder, "README.md");
        if (readme.exists()) {
            try {
                String content = Files.readString(readme.toPath());
                Matcher badgeMatcher = Pattern.compile("(?i)Difficulty-(Easy|Medium|Hard)").matcher(content);
                if (badgeMatcher.find()) {
                    String d = badgeMatcher.group(1);
                    return Character.toUpperCase(d.charAt(0)) + d.substring(1).toLowerCase();
                }
                Matcher h3Matcher = Pattern.compile("(?i)<h3>\\s*(Easy|Medium|Hard)\\s*</h3>").matcher(content);
                if (h3Matcher.find()) {
                    String d = h3Matcher.group(1);
                    return Character.toUpperCase(d.charAt(0)) + d.substring(1).toLowerCase();
                }
            } catch (Exception ignored) {}
        }

        return "Medium";
    }

    static String getTopic(String name, Map<String, List<String>> topicsFromReadme) {
        int num = getNumber(name);
        String slug = name.replaceAll("^\\d+-", "");
        String paddedName = String.format("%04d-%s", num, slug);
        String unpaddedName = num + "-" + slug;

        List<String> found = topicsFromReadme.get(name);
        if (found == null) found = topicsFromReadme.get(paddedName);
        if (found == null) found = topicsFromReadme.get(unpaddedName);

        if (found != null && !found.isEmpty()) {
            return String.join(", ", found);
        }

        String lower = name.toLowerCase();
        for (Map.Entry<String, String> e : KEYWORD_TOPICS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return "DSA";
    }

    static Map<String, List<String>> parseTopicsFromReadme(String readme) {
        Map<String, List<String>> map = new HashMap<>();
        int start = readme.indexOf(TOPICS_START);
        int end   = readme.indexOf(TOPICS_END);
        if (start == -1 || end == -1) return map;

        String topicBlock = readme.substring(start, end);
        String[] lines = topicBlock.split("\r?\n");
        String currentTopic = null;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("## ")) {
                currentTopic = line.substring(3).trim();
            } else if (currentTopic != null && line.startsWith("| [")) {
                Matcher m = Pattern.compile("\\[([^\\]]+)\\]").matcher(line);
                if (m.find()) {
                    String problemFolder = m.group(1).trim();
                    map.computeIfAbsent(problemFolder, k -> new ArrayList<>()).add(currentTopic);
                }
            }
        }
        return map;
    }

    static Map<String, String> parseStatsDifficulty() {
        Map<String, String> map = new HashMap<>();
        File stats = new File("stats.json");
        if (!stats.exists()) return map;
        try {
            String json = Files.readString(stats.toPath());
            Matcher m = Pattern.compile("\"(\\d+[^\"]+)\":\\{(?:[^{}]|\\{[^{}]*\\})*\"difficulty\":\"(\\w+)\"").matcher(json);
            while (m.find()) {
                String d = m.group(2);
                map.put(m.group(1), Character.toUpperCase(d.charAt(0)) + d.substring(1).toLowerCase());
            }
        } catch (Exception ignored) {}
        return map;
    }
}
