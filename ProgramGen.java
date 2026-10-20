import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Random VALID SPL program generator + smoke driver for Lexer+Parser.
 *
 * Writes one program to SPL.txt, runs `java Parser SPL.txt`, and counts
 * exit codes. Any non-zero exit means either the generator produced an
 * invalid program (bug in this file) or the parser rejected a valid one
 * (bug in Parser.java). Failing programs are saved to tests/fail_N.txt.
 *
 * usage: java ProgramGen N [--seed S] [--depth D]
 */
public final class ProgramGen {

    private final Random rng;
    private final int maxDepth;

    public ProgramGen(long seed, int maxDepth) {
        this.rng = new Random(seed);
        this.maxDepth = maxDepth;
    }

    // ------------------------------------------------------------------
    // token emitters
    // ------------------------------------------------------------------

    private String genNum() {
        double r = rng.nextDouble();
        String sign = rng.nextDouble() < 0.30 ? "-" : "";
        if (r < 0.15) return "0";
        if (r < 0.40) {                                        // 0.<d>*[1-9]
            StringBuilder sb = new StringBuilder(sign).append("0.");
            int mid = rng.nextInt(3);
            for (int i = 0; i < mid; i++) sb.append(rng.nextInt(10));
            sb.append(1 + rng.nextInt(9));
            return sb.toString();
        }
        if (r < 0.70) {                                        // [1-9]<d>*
            StringBuilder sb = new StringBuilder(sign);
            sb.append(1 + rng.nextInt(9));
            int rest = rng.nextInt(4);
            for (int i = 0; i < rest; i++) sb.append(rng.nextInt(10));
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(sign);            // [1-9]<d>*.<d>*[1-9]
        sb.append(1 + rng.nextInt(9));
        int rest = rng.nextInt(4);
        for (int i = 0; i < rest; i++) sb.append(rng.nextInt(10));
        sb.append('.');
        int mid = rng.nextInt(3);
        for (int i = 0; i < mid; i++) sb.append(rng.nextInt(10));
        sb.append(1 + rng.nextInt(9));
        return sb.toString();
    }

    private static final String STRING_CHARS =
        ",.:?!0123456789abcdefghijklmnopqrstuvwxyz-";

    private String genString() {
        int len = rng.nextInt(7);
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < len; i++)
            sb.append(STRING_CHARS.charAt(rng.nextInt(STRING_CHARS.length())));
        sb.append('"');
        return sb.toString();
    }

    private static final String NAME_CHARS =
        "abcdefghijklmnopqrstuvwxyz0123456789";

    private String genName() {
        int len = rng.nextInt(6);
        StringBuilder sb = new StringBuilder("#");
        for (int i = 0; i < len; i++)
            sb.append(NAME_CHARS.charAt(rng.nextInt(NAME_CHARS.length())));
        return sb.toString();
    }

    private String pick(String... xs) {
        return xs[rng.nextInt(xs.length)];
    }

    // ------------------------------------------------------------------
    // grammar-driven generators, each returns a flat token list
    // ------------------------------------------------------------------

    private List<String> genTerm(int d) {
        List<String> t = new ArrayList<>();
        if (d <= 0) {
            t.add(rng.nextBoolean() ? genName() : genNum());
            return t;
        }
        double r = rng.nextDouble();
        if (r < 0.25) { t.add(genName()); return t; }          // TERM -> NAME
        if (r < 0.45) { t.add(genNum());  return t; }          // TERM -> NUM
        if (r < 0.55) {                                        // TERM -> CALL
            t.add(genName()); t.add("(");
            t.addAll(genInput(d - 1));
            t.add(")");
            return t;
        }
        if (r < 0.90) {                                        // TERM -> op ( T T )
            t.add(pick("mod", "add", "sub", "mul", "div"));
            t.add("(");
            t.addAll(genTerm(d - 1));
            t.addAll(genTerm(d - 1));
            t.add(")");
            return t;
        }
        t.add("neg"); t.add("(");                              // TERM -> neg ( T )
        t.addAll(genTerm(d - 1));
        t.add(")");
        return t;
    }

    private List<String> genInput(int d) {
        List<String> t = new ArrayList<>();
        if (d <= 0) return t;                                   // INPUT -> epsilon
        int n = rng.nextInt(3);
        for (int i = 0; i < n; i++) t.addAll(genTerm(d));
        return t;
    }

    private List<String> genBool(int d) {
        List<String> t = new ArrayList<>();
        if (d <= 0) {
            t.add(pick("eq", "larger", "lesser"));
            t.add("(");
            t.addAll(genTerm(0));
            t.addAll(genTerm(0));
            t.add(")");
            return t;
        }
        double r = rng.nextDouble();
        if (r < 0.20) {                                         // not ( BOOL )
            t.add("not"); t.add("(");
            t.addAll(genBool(d - 1));
            t.add(")");
            return t;
        }
        if (r < 0.40) {                                         // and/or ( BOOL BOOL )
            t.add(pick("and", "or"));
            t.add("(");
            t.addAll(genBool(d - 1));
            t.addAll(genBool(d - 1));
            t.add(")");
            return t;
        }
        t.add(pick("eq", "larger", "lesser"));                  // eq/larger/lesser ( T T )
        t.add("(");
        t.addAll(genTerm(d - 1));
        t.addAll(genTerm(d - 1));
        t.add(")");
        return t;
    }

    private List<String> genOutp(int d) {
        List<String> t = new ArrayList<>();
        if (rng.nextBoolean()) { t.add(genString()); return t; }
        t.add("(");
        t.addAll(genTerm(d));
        t.add(")");
        return t;
    }

    private List<String> genInstr(int d) {
        List<String> choices = new ArrayList<>(
            List.of("nop", "comment", "print", "assign", "call"));
        if (d > 0) { choices.add("branch"); choices.add("loop"); }
        String which = choices.get(rng.nextInt(choices.size()));
        List<String> t = new ArrayList<>();
        switch (which) {
            case "nop":     t.add("nop"); return t;
            case "comment": t.add("comment"); t.add(genString()); return t;
            case "print":   t.add("print"); t.addAll(genOutp(d)); return t;
            case "assign":  t.add(genName()); t.add("=");
                            t.addAll(genTerm(d)); return t;
            case "call":    t.add(genName()); t.add("(");
                            t.addAll(genInput(d)); t.add(")"); return t;
            case "branch":
                t.add("if"); t.addAll(genBool(d - 1));
                t.add("then"); t.add("{");
                t.addAll(genAlgo(d - 1));
                t.add("}"); t.add("else"); t.add("{");
                t.addAll(genAlgo(d - 1));
                t.add("}");
                return t;
            case "loop":
                if (rng.nextBoolean()) {                        // COND BOOL do { ALGO }
                    t.add(pick("while", "until"));
                    t.addAll(genBool(d - 1));
                    t.add("do"); t.add("{");
                    t.addAll(genAlgo(d - 1));
                    t.add("}");
                    return t;
                }
                t.add("do"); t.add("{");                        // do { ALGO } COND BOOL
                t.addAll(genAlgo(d - 1));
                t.add("}");
                t.add(pick("while", "until"));
                t.addAll(genBool(d - 1));
                return t;
            default: throw new IllegalStateException(which);
        }
    }

    private List<String> genAlgo(int d) {
        List<String> t = new ArrayList<>();
        int n = d > 0 ? rng.nextInt(5) : rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            t.addAll(genInstr(d));
            t.add(";");                                         // terminator, not separator
        }
        return t;
    }

    private List<String> genFType(int d) {
        List<String> t = new ArrayList<>();
        boolean isNum = rng.nextBoolean();
        t.add(isNum ? "num" : "void");
        t.add(genName());
        t.add("(");
        int p = rng.nextInt(3);
        for (int i = 0; i < p; i++) t.add(genName());
        t.add(")");
        t.add("{");
        t.addAll(genP(d - 1));
        t.add("return");
        if (isNum) { t.add("("); t.addAll(genTerm(d - 1)); t.add(")"); }
        t.add("}");
        return t;
    }

    private List<String> genP(int d) {
        List<String> t = new ArrayList<>();
        int v = rng.nextInt(4);
        for (int i = 0; i < v; i++) t.add(genName());           // V_DECL
        t.add(":");
        if (d > 0) {
            int f = rng.nextInt(3);
            for (int i = 0; i < f; i++) t.addAll(genFType(d - 1));  // F_DECL
        }
        t.add(":");
        t.addAll(genAlgo(d));                                   // ALGO
        return t;
    }

    public String genProgram() {
        return String.join(" ", genP(maxDepth));
    }

    // ------------------------------------------------------------------
    // driver
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java ProgramGen N [--seed S] [--depth D]");
            System.exit(2);
        }
        int n = Integer.parseInt(args[0]);
        long seed = System.nanoTime();
        int depth = 3;
        for (int i = 1; i < args.length; i++) {
            if ("--seed".equals(args[i]) && i + 1 < args.length)
                seed = Long.parseLong(args[++i]);
            else if ("--depth".equals(args[i]) && i + 1 < args.length)
                depth = Integer.parseInt(args[++i]);
        }

        Files.createDirectories(Paths.get("tests"));
        ProgramGen g = new ProgramGen(seed, depth);
        int fail = 0;
        for (int i = 0; i < n; i++) {
            String src = g.genProgram() + "\n";
            Files.write(Paths.get("SPL.txt"), src.getBytes(StandardCharsets.UTF_8));

            StringBuilder out = new StringBuilder();
            int code = runParser("SPL.txt", out);
            if (code != 0) {
                fail++;
                Path saved = Paths.get("tests", "fail_" + i + ".txt");
                Files.write(saved, src.getBytes(StandardCharsets.UTF_8));
                System.err.println("[FAIL #" + i + "] exit=" + code
                        + " saved " + saved);
                System.err.println("     " + out.toString().trim());
            }
        }
        System.out.println((n - fail) + "/" + n + " passed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static int runParser(String path, StringBuilder out)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(javaBin(), "Parser", path);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var in = p.getInputStream()) {
            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return p.waitFor();
    }

    /** $JAVA_HOME/bin/java for the running JDK, so we don't depend on PATH. */
    static String javaBin() {
        String home = System.getProperty("java.home");
        String exe = home + File.separator + "bin" + File.separator
                   + (File.separatorChar == '\\' ? "java.exe" : "java");
        return new File(exe).canExecute() ? exe : "java";
    }
}