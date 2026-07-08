import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class UpdateReadme {

    static final String TOPICS_START = "<!---LeetCode Topics Start-->";
    static final String TOPICS_END   = "<!---LeetCode Topics End-->";

    static final Map<String, String> TOPICS = new LinkedHashMap<>();
    static {
        TOPICS.put("palindrome",  "Math");
        TOPICS.put("reverse",     "Math");
        TOPICS.put("digit",       "Math");
        TOPICS.put("add",         "Math");
        TOPICS.put("math",        "Math");
        TOPICS.put("string",      "String");
        TOPICS.put("array",       "Array");
        TOPICS.put("dynamic",     "Dynamic Programming");
        TOPICS.put("tree",        "Tree");
        TOPICS.put("graph",       "Graph");
        TOPICS.put("binary",      "Binary Search");
        TOPICS.put("hash",        "Hash Table");
        TOPICS.put("linked",      "Linked List");
        TOPICS.put("stack",       "Stack");
        TOPICS.put("queue",       "Queue");
        TOPICS.put("greedy",      "Greedy");
        TOPICS.put("backtrack",   "Backtracking");
        TOPICS.put("sort",        "Sorting");
        TOPICS.put("sliding",     "Sliding Window");
        TOPICS.put("two-pointer", "Two Pointers");
    }

    public static void main(String[] args) throws Exception {
        File root = new File(".");
        List<File> folders = new ArrayList<>();

        for (File f : root.listFiles()) {
            if (f.isDirectory() && f.getName().matches("^\\d+.*") && !f.getName().startsWith(".")) {
                folders.add(f);
            }
        }
        folders.sort(Comparator.comparingInt(f -> getNumber(f.getName())));

        Map<String, String> diffMap = parseDifficulty();

        StringBuilder rows = new StringBuilder();
        for (File f : folders) {
            String name  = f.getName();
            int    num   = getNumber(name);
            String title = getTitle(name);
            String diff  = diffMap.getOrDefault(name, "Medium");
            String topic = getTopic(name);
            String link  = "https://github.com/princegupt1234/leetcode-dSA/tree/main/" + name;
            rows.append(String.format("| %d | [%s](%s) | %s | %s |\n", num, title, link, diff, topic));
        }

        long easy   = diffMap.values().stream().filter(d -> d.equals("Easy")).count();
        long medium = diffMap.values().stream().filter(d -> d.equals("Medium")).count();
        long hard   = diffMap.values().stream().filter(d -> d.equals("Hard")).count();

        String mySection =
            "# \uD83E\uDDE0 LeetCode DSA Solutions\n\n" +
            "A collection of LeetCode problems solved in **Java**, organized by problem number.\n\n" +
            "## \uD83D\uDCC1 Structure\n\n" +
            "Each folder is named as `<problem-number>-<problem-name>` and contains the Java solution.\n\n" +
            "## \u2705 Problems Solved\n\n" +
            "| # | Problem | Difficulty | Topic |\n" +
            "|---|---------|------------|-------|\n" +
            rows + "\n" +
            "## \uD83D\uDE80 How to Run\n\n" +
            "```bash\n" +
            "javac Solution.java\n" +
            "java Solution\n" +
            "```\n\n" +
            "## \uD83D\uDEE0\uFE0F Language\n\n" +
            "- Java\n\n" +
            "## \uD83D\uDCC8 Progress\n\n" +
            "![Solved](https://img.shields.io/badge/Solved-" + folders.size() + "-brightgreen) " +
            "![Easy](https://img.shields.io/badge/Easy-" + easy + "-green) " +
            "![Medium](https://img.shields.io/badge/Medium-" + medium + "-orange) " +
            "![Hard](https://img.shields.io/badge/Hard-" + hard + "-red)\n\n";

        String existing = Files.exists(Path.of("README.md"))
            ? Files.readString(Path.of("README.md")) : "";

        String topicsSection = "";
        int start = existing.indexOf(TOPICS_START);
        int end   = existing.indexOf(TOPICS_END);
        if (start != -1 && end != -1) {
            topicsSection = existing.substring(start, end + TOPICS_END.length()) + "\n";
        }

        Files.writeString(Path.of("README.md"), mySection + topicsSection);
        System.out.println("README updated with " + folders.size() + " problems.");
    }

    static int getNumber(String name) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 9999;
    }

    static String getTitle(String name) {
        String[] words = name.replaceAll("^\\d+-", "").split("-");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty())
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    static String getTopic(String name) {
        String lower = name.toLowerCase();
        for (Map.Entry<String, String> e : TOPICS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return "DSA";
    }

    static Map<String, String> parseDifficulty() throws Exception {
        Map<String, String> map = new HashMap<>();
        File stats = new File("stats.json");
        if (!stats.exists()) return map;
        String json = Files.readString(stats.toPath());
        Matcher m = Pattern.compile("\"(\\d+[^\"]+)\":\\{[^}]*\"difficulty\":\"(\\w+)\"").matcher(json);
        while (m.find()) {
            String d = m.group(2);
            map.put(m.group(1), d.substring(0, 1).toUpperCase() + d.substring(1).toLowerCase());
        }
        return map;
    }
}
