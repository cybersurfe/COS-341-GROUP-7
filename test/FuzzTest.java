import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/*
differential fuzz test of the whole compiler.
for every random valid SPL program (SemGen):
  1. our compiler must accept it                             (else: compiler bug or generator bug)
  2. g++ must accept the generated C++                       (else: code generator bug)
  3. running it must print exactly what the independent reference interpreter prints   (else: semantics bug)
  4. mutated copies (undeclared variable, double declaration, undeclared function, mod+div, mod+decimal) must be
     rejected with the RIGHT kind of error and must never crash the compiler.
programs whose behaviour is undefined in C (int overflow, mod by zero, inf/nan) or that run too long are skipped.

usage: java FuzzTest [count] [firstSeed] [threads]
*/
public final class FuzzTest {
    private static final Path DIR = Paths.get("/tmp/fuzz");
    private static final AtomicInteger passed = new AtomicInteger(), skipped = new AtomicInteger(), bugs = new AtomicInteger();
    private static final AtomicInteger mutPassed = new AtomicInteger(), floatProgs = new AtomicInteger(), withCalls = new AtomicInteger();
    private static final AtomicInteger outputChars = new AtomicInteger(), rejected = new AtomicInteger(), mutAccepted = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        long first = args.length > 1 ? Long.parseLong(args[1]) : 1;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : Math.max(2, Runtime.getRuntime().availableProcessors());
        Files.createDirectories(DIR);
        ExecutorService ex = Executors.newFixedThreadPool(threads, r -> new Thread(null, r, "fuzz", 1L << 28));
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final long seed = first + i;
            fs.add(ex.submit(() -> one(seed)));
        }
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        System.out.println("programs: " + count + "  verified-equal: " + passed + "  skipped(UB/too long): " + skipped
            + "  BUGS: " + bugs);
        System.out.println("  of the verified: float programs " + floatProgs + ", programs with calls " + withCalls
            + ", total chars of program output compared " + outputChars);
        System.out.println("mutation tests passed: " + mutPassed + "   token-mutants: rejected cleanly " + rejected + ", accepted+verified correct " + mutAccepted);
        System.exit(bugs.get() == 0 ? 0 : 1);
    }

    private static void one(long seed) {
        String src = SemGen.generate(seed);
        try {
            verify(seed, src, true, "seed " + seed);
            mutations(seed, src);
            tokenFuzz(seed, src);
        } catch (Throwable t) {
            bug(seed, src, "CRASH " + t);
        }
    }

    //compile, run the reference interpreter, build with g++, run, compare.
    //mustCompile: the program is known valid (else a rejection is simply fine)
    private static void verify(long seed, String src, boolean mustCompile, String label) throws Exception {
        Compiler.Output o;
        try {
            o = Compiler.compile(src);
        } catch (Compiler.CompileException e) {
            if (mustCompile) bug(seed, src, "compiler rejected a valid program (" + e.phase + "): " + e.getMessage());
            else rejected.incrementAndGet();
            return;
        }
        String expected;
        try {
            expected = RefInterp.run(o.root, o.types.isFloat, 3_000_000);
        } catch (RefInterp.Skip s) {
            skipped.incrementAndGet();
            return;
        } catch (IllegalStateException e) {
            bug(seed, src, "the compiler ACCEPTED a program that the reference interpreter finds invalid: " + e.getMessage());
            return;
        }
        Path cpp = DIR.resolve("p" + seed + "_" + Math.abs(src.hashCode()) + ".cpp"), bin = DIR.resolve(cpp.getFileName().toString().replace(".cpp", ".bin"));
        Files.write(cpp, o.cpp.getBytes(StandardCharsets.UTF_8));
        Res cc = System.getProperty("san") != null
            ? exec(60, "g++", "-std=c++11", "-w", "-O2", "-fsanitize=undefined,address", "-fno-sanitize-recover=undefined", cpp.toString(), "-o", bin.toString())
            : exec(30, "g++", "-std=c++11", "-w", cpp.toString(), "-o", bin.toString());
        if (cc.code != 0) { bug(seed, src, "g++ failed:\n" + cc.out + "\n--- generated:\n" + o.cpp); return; }
        Res run = exec(10, bin.toString());
        if (run.code != 0) { bug(seed, src, "generated program exited with " + run.code + "\n" + run.out); return; }
        if (!run.out.equals(expected)) {
            bug(seed, src, "OUTPUT MISMATCH\nexpected: " + expected + "\nactual  : " + run.out + "\n--- generated:\n" + o.cpp);
            return;
        }
        if (mustCompile) {
            passed.incrementAndGet();
            if (o.types.isFloat) floatProgs.incrementAndGet();
            if (o.cpp.contains("/* call of")) withCalls.incrementAndGet();
            outputChars.addAndGet(expected.length());
        } else mutAccepted.incrementAndGet();
        Files.deleteIfExists(cpp);
        Files.deleteIfExists(bin);
    }

    private static final String[] JUNK = {"(", ")", "{", "}", ":", ";", "=", "#a", "#zz", "#f", "0", "7", "-3", "0.5", "\"s\"", "add", "div", "mod", "neg",
        "not", "and", "eq", "larger", "if", "then", "else", "while", "until", "do", "print", "nop", "return", "void", "num", "comment"};

    //token-level mutations: delete / duplicate / swap neighbours / replace. outcome must be: rejected cleanly, or accepted and CORRECT
    private static void tokenFuzz(long seed, String src) throws Exception {
        java.util.Random rnd = new java.util.Random(seed * 31 + 7);
        String[] toks = src.trim().split(" +");
        for (int m = 0; m < 6; m++) {
            List<String> t = new ArrayList<>(java.util.Arrays.asList(toks));
            int i = rnd.nextInt(t.size());
            switch (rnd.nextInt(4)) {
                case 0: t.remove(i); break;
                case 1: t.add(i, t.get(i)); break;
                case 2: if (i + 1 < t.size()) java.util.Collections.swap(t, i, i + 1); break;
                default: t.set(i, JUNK[rnd.nextInt(JUNK.length)]);
            }
            String prog = String.join(" ", t);
            try {
                verify(seed, prog, false, "token-mutant of seed " + seed);
            } catch (Compiler.CompileException e) {
                //not reachable: verify catches it
            }
        }
    }

    private static void mutations(long seed, String src) {
        String base = src.trim();
        expectError(seed, src, "#undeclared9 = 1 ; ", base, "semantic", "variable '#undeclared9'", true);
        expectError(seed, src, "print ( add ( 1 #undeclared8 ) ) ; ", base, "semantic", "variable '#undeclared8'", true);
        expectError(seed, src, "#nofn7 ( ) ; ", base, "semantic", "function '#nofn7'", true);
        expectError(seed, src, "", "#dup #dup " + base, "semantic", "declared twice", false);
        String both = " print ( mod ( 1 2 ) ) ; print ( div ( 1 2 ) ) ; ";
        expectError(seed, src, both, base, "type", "float-integer-conflict", true);
        //a mod forces ints; a decimal number anywhere then is an error, unless the program already had a div (then W1 fires first)
        expectErrorAny(seed, src, " print ( mod ( 1 2 ) ) ; print ( 0.5 ) ; ", base, "type");
    }

    //text is appended at the very end (= end of the main ALGO) or the whole program is given as 'whole'
    private static void expectError(long seed, String orig, String append, String whole, String phase, String frag, boolean atEnd) {
        String prog = atEnd ? whole + " " + append : whole;
        check(seed, orig, prog, phase, frag);
    }
    private static void expectErrorAny(long seed, String orig, String append, String whole, String phase) {
        check(seed, orig, whole + " " + append, phase, null);
    }

    private static void check(long seed, String orig, String prog, String phase, String frag) {
        try {
            Compiler.compile(prog);
            bug(seed, prog, "MUTANT ACCEPTED, expected a " + phase + " error containing '" + frag + "'");
        } catch (Compiler.CompileException e) {
            if (!e.phase.equals(phase) || (frag != null && !e.getMessage().contains(frag)))
                bug(seed, prog, "mutant rejected with the wrong error: phase=" + e.phase + " msg=" + e.getMessage()
                    + "  (expected " + phase + " / " + frag + ")");
            else mutPassed.incrementAndGet();
        } catch (Throwable t) {
            bug(seed, prog, "mutant crashed the compiler: " + t);
        }
    }

    private static void bug(long seed, String src, String why) {
        bugs.incrementAndGet();
        try {
            Files.write(DIR.resolve("fail_" + seed + ".spl"), src.getBytes(StandardCharsets.UTF_8));
            Files.write(DIR.resolve("fail_" + seed + ".txt"), why.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        System.out.println("BUG seed " + seed + ": " + (why.length() > 300 ? why.substring(0, 300) + "..." : why));
    }

    private static final class Res { int code; String out; }

    private static Res exec(int timeoutSec, String... cmd) throws Exception {
        File f = File.createTempFile("run", ".out", DIR.toFile());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(f);
            Process p = pb.start();
            Res r = new Res();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                r.code = 124;
            } else r.code = p.exitValue();
            r.out = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return r;
        } finally {
            f.delete();
        }
    }
}
