import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs `java Parser` on every tests/invalid_*.txt and asserts:
 *   - exit code is 1 (not 0, not 2)
 *   - stderr contains "line <n>, column <n>" (so we know the message
 *     points at the offending token, not just "error somewhere")
 *
 * If you prefer to keep lexer-error fixtures separate from parser-error
 * fixtures, change the glob below to "syntax_invalid_*.txt" and rename
 * the six new files accordingly.
 *
 * usage: java InvalidTestRunner
 */
public final class InvalidTestRunner {

    private static final Pattern LOC = Pattern.compile("line \\d+, column \\d+");

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("tests");
        if (!Files.isDirectory(dir)) {
            System.err.println("no tests/ directory — create it first");
            System.exit(2);
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "invalid_*.txt")) {
            for (Path p : ds) files.add(p);
        }
        files.sort(Path::compareTo);
        if (files.isEmpty()) {
            System.err.println("no tests/invalid_*.txt found");
            System.exit(2);
        }

        int bad = 0;
        for (Path f : files) {
            StringBuilder out = new StringBuilder();
            int code = runParser(f.toString(), out);
            if (code != 1) {
                System.out.println("BAD: " + f + " exit=" + code + " (expected 1)");
                System.out.println("     " + out.toString().trim());
                bad++;
                continue;
            }
            if (!LOC.matcher(out).find()) {
                System.out.println("BAD: " + f + " has no line/column in message");
                System.out.println("     " + out.toString().trim());
                bad++;
                continue;
            }
            String first = out.toString().lines().findFirst().orElse("").trim();
            System.out.println("ok : " + f + " -> " + first);
        }
        if (bad > 0) {
            System.out.println(bad + " invalid test(s) misbehaved");
            System.exit(1);
        }
        System.out.println("all " + files.size() + " invalid tests behaved correctly");
    }

    private static int runParser(String path, StringBuilder out)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(ProgramGen.javaBin(), "Parser", path);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var in = p.getInputStream()) {
            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return p.waitFor();
    }
}