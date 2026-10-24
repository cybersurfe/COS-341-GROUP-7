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
 * End-to-end test runner.
 *  1. typedValid_*.spl  ->  spl.jar  ->  g++  ->  run  ->  diff with sibling .out
 *  2. typedInvalid_*.spl  ->  spl.jar  ->  assert exit != 0, stderr contains
 *     "line N, column N", output.cpp NOT produced
 *
 * usage: java IntegrationRunner
 */
public final class IntegrationRunner {

    private static final String JAR       = "spl.jar";
    private static final String OUT_CPP   = "output.cpp";
    private static final String EXE       = "run.out";
    private static final Pattern LOC      = Pattern.compile("line \\d+, column \\d+");

    public static void main(String[] args) throws Exception {
        if (!Files.exists(Paths.get(JAR))) {
            System.err.println("missing " + JAR + " — build the jar first");
            System.exit(2);
        }
        Path dir = Paths.get("tests");
        if (!Files.isDirectory(dir)) {
            System.err.println("no tests/ directory");
            System.exit(2);
        }

        List<Path> valids   = glob(dir, "typedValid_*.spl");
        List<Path> invalids = glob(dir, "typedInvalid_*.spl");
        if (valids.isEmpty() && invalids.isEmpty()) {
            System.err.println("no typed{Valid,Invalid}_*.spl found");
            System.exit(2);
        }

        int bad = 0;
        for (Path f : valids) {
            String err = oneValid(f);
            if (err == null) System.out.println("ok   valid   : " + f.getFileName());
            else { System.out.println("BAD  valid   : " + f.getFileName() + " -> " + err); bad++; }
        }
        for (Path f : invalids) {
            String err = oneInvalid(f);
            if (err == null) System.out.println("ok   invalid : " + f.getFileName());
            else { System.out.println("BAD  invalid : " + f.getFileName() + " -> " + err); bad++; }
        }

        int total = valids.size() + invalids.size();
        System.out.println(bad == 0
            ? "all " + total + " integration tests passed"
            : bad + " of " + total + " failed");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static List<Path> glob(Path dir, String pattern) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, pattern)) {
            for (Path p : ds) out.add(p);
        }
        out.sort(Path::compareTo);
        return out;
    }

    private static String oneValid(Path spl) {
        try {
            Files.deleteIfExists(Paths.get(OUT_CPP));
            Files.deleteIfExists(Paths.get(EXE));
            Path expected = spl.resolveSibling(spl.getFileName().toString().replace(".spl", ".out"));
            if (!Files.exists(expected)) return "missing " + expected.getFileName();

            Proc r = run(ProgramGen.javaBin(), "-jar", JAR, spl.toString(), OUT_CPP);
            if (r.exit != 0) return "jar exit=" + r.exit + ": " + truncate(r.out);
            if (!Files.exists(Paths.get(OUT_CPP))) return "no " + OUT_CPP + " produced";

            r = run("g++", "-std=c++17", "-O2", "-o", EXE, OUT_CPP);
            if (r.exit != 0) return "g++ failed: " + truncate(r.out);

            r = run("./" + EXE);
            if (r.exit != 0) return "run exit=" + r.exit + ": " + truncate(r.out);

            String exp = Files.readString(expected, StandardCharsets.UTF_8);
            if (!exp.equals(r.out)) return "output mismatch\n  expected: "
                + escape(exp) + "\n  actual:   " + escape(r.out);
            return null;
        } catch (IOException | InterruptedException e) {
            return "exception: " + e;
        }
    }

    private static String oneInvalid(Path spl) {
        try {
            Files.deleteIfExists(Paths.get(OUT_CPP));
            Proc r = run(ProgramGen.javaBin(), "-jar", JAR, spl.toString(), OUT_CPP);
            if (r.exit == 0) return "jar exited 0 on an invalid program";
            if (!LOC.matcher(r.out).find()) return "stderr has no 'line N, column N': " + truncate(r.out);
            if (Files.exists(Paths.get(OUT_CPP))) {
                Files.deleteIfExists(Paths.get(OUT_CPP));
                return "output.cpp was written on an invalid program";
            }
            return null;
        } catch (IOException | InterruptedException e) {
            return "exception: " + e;
        }
    }

    private static final class Proc {
        final int exit; final String out;
        Proc(int e, String o) { exit = e; out = o; }
    }

    private static Proc run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Proc(p.waitFor(), out);
    }

    private static String truncate(String s) {
        s = s.replace("\r", "").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}