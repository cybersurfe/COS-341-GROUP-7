package codegen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static codegen.CodeGen.*;

public class CodeGenTest {
    static int pass = 0, fail = 0, skip = 0;
    static boolean haveGpp;

    // ids used by hand-written tests: id -> "sys<id>"
    static final int X = 1, Y = 2, I = 3, Z = 4;
    static Map<Integer, String> NAMES = new HashMap<>();
    static {
        for (int k = 1; k <= 20; k++) NAMES.put(k, "sys" + k);
        NAMES.put(101, "sys1");           // second leaf node of the same variable as id 1
        NAMES.put(102, "sys1");
    }

    public static void main(String[] args) throws Exception {
        int runFuzz    = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        int structFuzz = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        long seed      = args.length > 2 ? Long.parseLong(args[2]) : 1;
        haveGpp = gppAvailable();
        if (!haveGpp) System.out.println("NOTE: g++ not found - compile-and-run tests are skipped");

        exactTextTests();
        errorTests();
        if (haveGpp) runTests(); else skip += 40;
        structuralFuzz(structFuzz, seed);
        if (haveGpp) differentialFuzz(runFuzz, seed); else skip += runFuzz;

        System.out.println("\n────────────");
        System.out.println("PASS: " + pass + "   FAIL: " + fail + "   SKIP: " + skip);
        if (fail > 0) System.exit(1);
    }

    // ───────────────────────────── AST helpers ─────────────────────────────
    static Algo algo(Instr... is) { Algo a = new Algo(); a.instrs.addAll(Arrays.asList(is)); return a; }
    static Name n(int id) { return new Name(id); }
    static Num num(int v) { return new Num(Integer.toString(v)); }
    static Term add(Term a, Term b) { return new BinOp("add", a, b); }
    static Term sub(Term a, Term b) { return new BinOp("sub", a, b); }
    static Term mul(Term a, Term b) { return new BinOp("mul", a, b); }
    static Term mod(Term a, Term b) { return new BinOp("mod", a, b); }
    static Bool_ eq(Term a, Term b)     { return new Cmp("eq", a, b); }
    static Bool_ larger(Term a, Term b) { return new Cmp("larger", a, b); }
    static Bool_ lesser(Term a, Term b) { return new Cmp("lesser", a, b); }
    static Instr set(int id, Term t) { return new Assign(id, t); }
    static Instr printT(Term t) { return new PrintTerm(t); }
    static Instr printS(String s) { return new PrintString(s); }
    static Instr incr(int id) { return set(id, add(n(id), num(1))); }

    static String gen(Algo a, String type) {
        CodeGen cg = new CodeGen(type);
        cg.setNames(NAMES);
        return cg.generate(a);
    }
    static String gen(Algo a) { return gen(a, "int"); }

    // ───────────────────────────── 1. exact text ─────────────────────────────
    static void exactTextTests() {
        System.out.println("== exact-text tests ==");

        check("empty algo", gen(algo()), "/* end */\n");

        check("print string (bare text)", gen(algo(printS("Hello"))),
            "std::cout << \"Hello\";\n/* end */\n");
        check("print string (token with quotes)", gen(algo(printS("\"Hello\""))),
            "std::cout << \"Hello\";\n/* end */\n");

        check("print term", gen(algo(printT(n(X)))),
            "int v1;\nv1 = sys1;\nstd::cout << v1;\n/* end */\n");

        check("nop / comment get ';'", gen(algo(new Nop(), new Comment("\"abc\""))),
            "/* nop */;\n/* \"abc\" */;\n/* end */\n");

        check("comment cannot terminate itself", gen(algo(new Comment("a*/b"))),
            "/* a* /b */;\n/* end */\n");

        check("assign num", gen(algo(set(X, num(5)))),
            "int v1;\nv1 = 5;\nsys1 = v1;\n/* end */\n");

        check("assign add(#y,1)", gen(algo(set(X, add(n(Y), num(1))))),
            "int v1;\nint v2;\nint v3;\nv2 = sys2;\nv3 = 1;\nv1 = v2 + v3;\nsys1 = v1;\n/* end */\n");

        check("float type declares float", gen(algo(set(X, num(1))), "float"),
            "float v1;\nv1 = 1;\nsys1 = v1;\n/* end */\n");

        check("neg", gen(algo(set(X, new Neg(n(Y))))),
            "int v1;\nint v2;\nv2 = sys2;\nv1 = -v2;\nsys1 = v1;\n/* end */\n");

        check("all five binary operators map correctly",
            gen(algo(set(X, new BinOp("mod", num(1), num(2))))).contains("v1 = v2 % v3;")
            && gen(algo(set(X, new BinOp("div", num(1), num(2))))).contains("v1 = v2 / v3;")
            && gen(algo(set(X, new BinOp("add", num(1), num(2))))).contains("v1 = v2 + v3;")
            && gen(algo(set(X, new BinOp("sub", num(1), num(2))))).contains("v1 = v2 - v3;")
            && gen(algo(set(X, new BinOp("mul", num(1), num(2))))).contains("v1 = v2 * v3;"));

        // two different leaf ids of the same variable resolve to the same sys name
        check("same variable via different leaf ids", gen(algo(set(101, n(102)))),
            "int v1;\nv1 = sys1;\nsys1 = v1;\n/* end */\n");

        check("negative literal kept", gen(algo(set(X, new Num("-5")))),
            "int v1;\nv1 = -5;\nsys1 = v1;\n/* end */\n");
        check("leading zeros stripped (octal trap)", gen(algo(set(X, new Num("010")))),
            "int v1;\nv1 = 10;\nsys1 = v1;\n/* end */\n");
        check("'09' would not compile in C++ unless stripped", gen(algo(set(X, new Num("09")))),
            "int v1;\nv1 = 9;\nsys1 = v1;\n/* end */\n");
        check("'-007' -> '-7'", gen(algo(set(X, new Num("-007")))),
            "int v1;\nv1 = -7;\nsys1 = v1;\n/* end */\n");
        check("'0' / '00' -> '0'", gen(algo(set(X, new Num("00")))),
            "int v1;\nv1 = 0;\nsys1 = v1;\n/* end */\n");
        check("decimals untouched", gen(algo(set(X, new Num("007.50")))),
            "int v1;\nv1 = 007.50;\nsys1 = v1;\n/* end */\n");

        // if/else: decision, false code, goto Lc, then code, Lc
        Algo t = algo(printT(n(X)));
        Algo e = algo(printT(n(Y)));
        check("if/else layout", gen(algo(new Branch(eq(n(X), n(Y)), t, e))),
            "/* if */\n" +
            "int v1;\nint v2;\nv1 = sys1;\nv2 = sys2;\n" +
            "if (v1 == v2) goto L1;\ngoto L2;\n" +
            "L2: /* else */\n" +
            "int v4;\nv4 = sys2;\nstd::cout << v4;\n/* end */\n" +
            "goto L3;\n" +
            "L1: /* then */\n" +
            "int v3;\nv3 = sys1;\nstd::cout << v3;\n/* end */\n" +
            "L3: /* continue */;\n" +
            "/* end */\n");

        String o5 = gen(algo(new Branch(new Not(eq(n(X), num(0))), algo(), algo())));
        check("not() swaps targets", o5.contains("if (v1 == v2) goto L2;") && o5.contains("goto L1;"));

        String o5b = gen(algo(new Branch(new Not(new Not(eq(n(X), num(0)))), algo(), algo())));
        check("not(not()) restores targets", o5b.contains("if (v1 == v2) goto L1;") && o5b.contains("goto L2;"));

        // while: ls=L1 le=L2 d=L3, body jumps back to ls
        check("while-do layout",
            gen(algo(new Loop(LoopKind.WHILE_DO, lesser(n(I), num(10)), algo(incr(I))))),
            "L1: /* until */\n" +
            "int v1;\nint v2;\nv1 = sys3;\nv2 = 10;\n" +
            "if (v1 < v2) goto L3;\ngoto L2;\n" +
            "L3: /* do */\n" +
            "int v3;\nint v4;\nint v5;\nv4 = sys3;\nv5 = 1;\nv3 = v4 + v5;\nsys3 = v3;\n/* end */\n" +
            "goto L1;\n" +
            "L2:\n/* exit */;\n" +
            "/* end */\n");

        check("until-do exits when condition true",
            gen(algo(new Loop(LoopKind.UNTIL_DO, larger(n(I), num(2)), algo()))),
            "L1: /* until */\n" +
            "int v1;\nint v2;\nv1 = sys3;\nv2 = 2;\n" +
            "if (v1 > v2) goto L2;\ngoto L3;\n" +
            "L3: /* do */\n/* end */\n" +
            "goto L1;\n" +
            "L2:\n/* exit */;\n" +
            "/* end */\n");

        check("do-while layout",
            gen(algo(new Loop(LoopKind.DO_WHILE, lesser(n(I), num(3)), algo()))),
            "L1: /* do */\n" +
            "/* end */\n" +
            "/* while */\n" +
            "int v1;\nint v2;\nv1 = sys3;\nv2 = 3;\n" +
            "if (v1 < v2) goto L1;\ngoto L2;\n" +
            "L2: /* exit */;\n" +
            "/* end */\n");

        check("do-until layout (not applied)",
            gen(algo(new Loop(LoopKind.DO_UNTIL, lesser(n(I), num(3)), algo()))),
            "L1: /* do */\n" +
            "/* end */\n" +
            "/* while */\n" +
            "int v1;\nint v2;\nv1 = sys3;\nv2 = 3;\n" +
            "if (v1 < v2) goto L2;\ngoto L1;\n" +
            "L2: /* exit */;\n" +
            "/* end */\n");

        // and / or cascades
        String oa = gen(algo(new Branch(new And(eq(num(1), num(1)), eq(num(2), num(2))), algo(), algo())));
        check("and: first false jumps straight to false label", oa.contains("goto L2;") && oa.contains("L4:"));

        // generator is re-usable and deterministic
        CodeGen cg = new CodeGen("int"); cg.setNames(NAMES);
        Algo same = algo(set(X, num(1)), new Branch(eq(n(X), num(1)), algo(), algo()));
        check("generate() twice gives identical text", cg.generate(same).equals(cg.generate(same)));
    }

    // ───────────────────────────── 2. errors ─────────────────────────────
    static void errorTests() {
        System.out.println("== error tests ==");
        expectThrows("unknown NAME id throws (no silent fallback)", IllegalStateException.class,
            () -> gen(algo(printT(n(999)))));
        expectThrows("unknown assign target throws", IllegalStateException.class,
            () -> gen(algo(set(999, num(1)))));
        expectThrows("unknown binop", IllegalArgumentException.class,
            () -> gen(algo(set(X, new BinOp("pow", num(1), num(2))))));
        expectThrows("unknown cmp", IllegalArgumentException.class,
            () -> gen(algo(new Branch(new Cmp("neq", num(1), num(2)), algo(), algo()))));
    }

    // ───────────────────────────── 3. compile & run ─────────────────────────────
    static void runTests() {
        System.out.println("== compile-and-run tests ==");
        runCase("print string", algo(printS("Hello")), "int", "Hello");
        runCase("print string token", algo(printS("\"a1-b?\"")), "int", "a1-b?");
        runCase("empty program", algo(), "int", "");
        runCase("assign + print", algo(set(X, num(5)), printT(n(X))), "int", "5");
        runCase("add/mul", algo(printT(add(num(1), mul(num(2), num(3))))), "int", "7");
        runCase("sub gives negative", algo(printT(sub(num(3), num(10)))), "int", "-7");
        runCase("mod", algo(printT(mod(num(10), num(3)))), "int", "1");
        runCase("neg", algo(printT(new Neg(num(5)))), "int", "-5");
        runCase("neg(neg(x))", algo(printT(new Neg(new Neg(num(5))))), "int", "5");
        runCase("negative literals: sub(-3 -4)", algo(printT(sub(new Num("-3"), new Num("-4")))), "int", "1");
        runCase("octal trap 010", algo(printT(new Num("010"))), "int", "10");
        runCase("octal trap 09", algo(printT(new Num("09"))), "int", "9");
        runCase("same variable, different leaf ids",
            algo(set(101, num(7)), printT(n(102)), printT(n(X))), "int", "77");
        runCase("self-referential assign x=x+x", algo(set(X, num(4)), set(X, add(n(X), n(X))), printT(n(X))), "int", "8");
        runCase("float div", algo(printT(new BinOp("div", num(10), num(4)))), "float", "2.5");
        runCase("float decimal literal arithmetic",
            algo(printT(add(new Num("0.5"), new Num("0.25")))), "float", "0.75");

        // branch
        runCase("if true", algo(new Branch(eq(num(1), num(1)), algo(printS("T")), algo(printS("F")))), "int", "T");
        runCase("if false", algo(new Branch(eq(num(1), num(2)), algo(printS("T")), algo(printS("F")))), "int", "F");
        runCase("larger / lesser",
            algo(new Branch(larger(num(2), num(1)), algo(printS("a")), algo(printS("b"))),
                 new Branch(lesser(num(2), num(1)), algo(printS("c")), algo(printS("d")))), "int", "ad");
        runCase("branches with empty arms", algo(new Branch(eq(num(1), num(1)), algo(), algo()), printS("ok")), "int", "ok");
        runCase("nested branches",
            algo(new Branch(eq(num(1), num(1)),
                    algo(new Branch(eq(num(2), num(3)), algo(printS("a")), algo(printS("b")))),
                    algo(printS("c")))), "int", "b");
        runCase("branch in else arm only",
            algo(new Branch(eq(num(1), num(2)), algo(printS("a")),
                    algo(new Branch(eq(num(2), num(2)), algo(printS("b")), algo(printS("c")))))), "int", "b");

        // loops
        runCase("while-do counts", algo(set(I, num(0)),
            new Loop(LoopKind.WHILE_DO, lesser(n(I), num(3)), algo(printT(n(I)), incr(I)))), "int", "012");
        runCase("while-do never runs", algo(set(I, num(5)),
            new Loop(LoopKind.WHILE_DO, lesser(n(I), num(3)), algo(printT(n(I)), incr(I))), printS("e")), "int", "e");
        runCase("until-do counts", algo(set(I, num(0)),
            new Loop(LoopKind.UNTIL_DO, larger(n(I), num(2)), algo(printT(n(I)), incr(I)))), "int", "012");
        runCase("until-do never runs", algo(set(I, num(9)),
            new Loop(LoopKind.UNTIL_DO, larger(n(I), num(2)), algo(printT(n(I)), incr(I))), printS("e")), "int", "e");
        runCase("do-while runs once when false", algo(set(I, num(5)),
            new Loop(LoopKind.DO_WHILE, lesser(n(I), num(3)), algo(printT(n(I)), incr(I)))), "int", "5");
        runCase("do-while counts", algo(set(I, num(0)),
            new Loop(LoopKind.DO_WHILE, lesser(n(I), num(3)), algo(printT(n(I)), incr(I)))), "int", "012");
        runCase("do-until runs once when true", algo(set(I, num(9)),
            new Loop(LoopKind.DO_UNTIL, larger(n(I), num(2)), algo(printT(n(I)), incr(I)))), "int", "9");
        runCase("do-until counts", algo(set(I, num(0)),
            new Loop(LoopKind.DO_UNTIL, larger(n(I), num(2)), algo(printT(n(I)), incr(I)))), "int", "012");
        runCase("loop with not() condition", algo(set(I, num(0)),
            new Loop(LoopKind.WHILE_DO, new Not(eq(n(I), num(3))), algo(printT(n(I)), incr(I)))), "int", "012");
        runCase("loop with and/or condition", algo(set(I, num(0)),
            new Loop(LoopKind.WHILE_DO,
                new And(lesser(n(I), num(10)), new Or(lesser(n(I), num(2)), eq(n(I), num(4)))),
                algo(printT(n(I)), incr(I)))), "int", "01");

        // nested loops + branch inside loop
        runCase("nested loops (2x3 grid)", algo(set(X, num(0)),
            new Loop(LoopKind.WHILE_DO, lesser(n(X), num(2)), algo(
                set(Y, num(0)),
                new Loop(LoopKind.WHILE_DO, lesser(n(Y), num(3)), algo(
                    printT(add(mul(n(X), num(10)), n(Y))), printS(","), incr(Y))),
                incr(X)))), "int", "0,1,2,10,11,12,");
        runCase("same-kind nested loops get distinct labels", algo(set(X, num(0)),
            new Loop(LoopKind.DO_UNTIL, eq(n(X), num(2)), algo(
                set(Y, num(0)),
                new Loop(LoopKind.DO_UNTIL, eq(n(Y), num(2)), algo(printS("*"), incr(Y))),
                incr(X)))), "int", "****");
        runCase("branch inside loop (evens)", algo(set(I, num(0)),
            new Loop(LoopKind.WHILE_DO, lesser(n(I), num(6)), algo(
                new Branch(eq(mod(n(I), num(2)), num(0)), algo(printT(n(I))), algo(new Nop())),
                incr(I)))), "int", "024");
        runCase("gcd(48,18) = 6", algo(set(X, num(48)), set(Y, num(18)),
            new Loop(LoopKind.WHILE_DO, larger(n(Y), num(0)), algo(
                set(Z, mod(n(X), n(Y))), set(X, n(Y)), set(Y, n(Z)))),
            printT(n(X))), "int", "6");

        // boolean truth tables: every formula up to depth 2 over {true,false}
        List<Bool_> fs = new ArrayList<>(); List<Boolean> vals = new ArrayList<>();
        boolFormulas(2, fs, vals);
        Algo tt = new Algo(); StringBuilder expect = new StringBuilder();
        for (int k = 0; k < fs.size(); k++) {
            tt.instrs.add(new Branch(fs.get(k), algo(printS("1")), algo(printS("0"))));
            expect.append(vals.get(k) ? "1" : "0");
        }
        runCase("truth table: " + fs.size() + " and/or/not formulas", tt, "int", expect.toString());

        // stress
        Term deep = num(1);
        for (int k = 0; k < 150; k++) deep = new Neg(deep);
        runCase("150 nested neg", algo(printT(deep)), "int", "1");
        Term chain = num(0);
        for (int k = 0; k < 100; k++) chain = add(chain, num(1));
        runCase("100-deep add chain", algo(printT(chain)), "int", "100");
        Algo many = new Algo(); StringBuilder ex = new StringBuilder();
        for (int k = 0; k < 500; k++) { many.instrs.add(printT(num(k % 10))); ex.append(k % 10); }
        runCase("500 sequential prints", many, "int", ex.toString());
        Bool_ big = eq(num(0), num(1));
        for (int k = 0; k < 40; k++) big = new Or(big, eq(num(k), num(39)));
        runCase("40-way or chain", algo(new Branch(big, algo(printS("y")), algo(printS("n")))), "int", "y");
    }

    static void boolFormulas(int depth, List<Bool_> fs, List<Boolean> vals) {
        List<Bool_> cur = new ArrayList<>(); List<Boolean> cv = new ArrayList<>();
        cur.add(eq(num(1), num(1))); cv.add(true);
        cur.add(eq(num(1), num(2))); cv.add(false);
        for (int d = 0; d < depth; d++) {
            List<Bool_> nx = new ArrayList<>(cur); List<Boolean> nv = new ArrayList<>(cv);
            for (int a = 0; a < cur.size(); a++) {
                nx.add(new Not(cur.get(a))); nv.add(!cv.get(a));
                for (int b = 0; b < cur.size(); b++) {
                    nx.add(new And(cur.get(a), cur.get(b))); nv.add(cv.get(a) && cv.get(b));
                    nx.add(new Or(cur.get(a), cur.get(b)));  nv.add(cv.get(a) || cv.get(b));
                }
            }
            cur = nx; cv = nv;
            if (cur.size() > 3000) break;
        }
        fs.addAll(cur); vals.addAll(cv);
    }

    static void runCase(String name, Algo a, String type, String expected) {
        String code = gen(a, type);
        String err = labelProblems(code);
        if (err != null) { bad(name, err + "\n" + code); return; }
        try {
            String actual = compileAndRun(code, type, NAMES.values());
            if (actual.equals(expected)) ok(name);
            else bad(name, "expected stdout [" + abbreviate(expected) + "] got [" + abbreviate(actual) + "]\n" + code);
        } catch (Exception e) {
            bad(name, e.getMessage() + "\n" + code);
        }
    }

    // ───────────────────────────── 4. structural fuzz ─────────────────────────────
    static void structuralFuzz(int count, long seed) {
        System.out.println("== structural fuzz (" + count + " programs) ==");
        int badCount = 0;
        for (int k = 0; k < count; k++) {
            Algo a = new Gen(new Random(seed * 100003 + k)).algo(3, 0);
            String code;
            try { code = gen(a); }
            catch (RuntimeException e) { bad("struct#" + k, "generator threw " + e); badCount++; continue; }
            String err = labelProblems(code);
            if (err != null) { bad("struct#" + k, err); badCount++; }
        }
        if (badCount == 0) ok("structural fuzz: labels defined once, gotos resolve, temps declared once");
    }

    static final Pattern LABEL_DEF = Pattern.compile("^(L\\d+):", Pattern.MULTILINE);
    static final Pattern GOTO = Pattern.compile("goto (L\\d+);");
    static final Pattern DECL = Pattern.compile("^(?:int|float) (v\\d+);$", Pattern.MULTILINE);

    static String labelProblems(String code) {
        Set<String> defs = new HashSet<>();
        Matcher m = LABEL_DEF.matcher(code);
        while (m.find()) if (!defs.add(m.group(1))) return "duplicate label " + m.group(1);
        m = GOTO.matcher(code);
        while (m.find()) if (!defs.contains(m.group(1))) return "goto to undefined label " + m.group(1);
        Set<String> decls = new HashSet<>();
        m = DECL.matcher(code);
        while (m.find()) if (!decls.add(m.group(1))) return "temp declared twice: " + m.group(1);
        return null;
    }

    // ───────────────────────────── 5. differential fuzz ─────────────────────────────
    static void differentialFuzz(int count, long seed) {
        System.out.println("== differential fuzz (" + count + " programs vs g++) ==");
        int ran = 0, skipped = 0, badCount = 0;
        for (int k = 0; k < count; k++) {
            Algo a = new Gen(new Random(seed * 7919 + k)).algo(3, 0);
            String expected;
            try { expected = new Interp().run(a); }
            catch (ArithmeticException | StackOverflowError | Interp.TooLong e) { skipped++; continue; }
            String code = gen(a);
            try {
                String actual = compileAndRun(code, "int", NAMES.values());
                ran++;
                if (!actual.equals(expected)) {
                    badCount++;
                    bad("diff#" + k, "expected [" + abbreviate(expected) + "] got [" + abbreviate(actual) + "]\n" + code);
                }
            } catch (Exception e) {
                badCount++;
                bad("diff#" + k, e.getMessage() + "\n" + code);
            }
        }
        if (badCount == 0) ok("differential fuzz: " + ran + " programs agree with the reference interpreter (" + skipped + " skipped)");
    }

    static final class Gen {
        final Random r;
        Gen(Random r) { this.r = r; }

        Algo algo(int depth, int loopDepth) {
            Algo a = new Algo();
            int len = r.nextInt(4);
            for (int i = 0; i < len; i++) a.instrs.addAll(instr(depth, loopDepth));
            return a;
        }

        List<Instr> instr(int depth, int loopDepth) {
            List<Instr> out = new ArrayList<>();
            int pick = r.nextInt(depth > 0 ? 9 : 5);
            switch (pick) {
                case 0: out.add(printS("s" + r.nextInt(10))); break;
                case 1: case 2: out.add(printT(term(2))); break;
                case 3: out.add(r.nextBoolean() ? new Nop() : new Comment("\"c" + r.nextInt(9) + "\"")); break;
                case 4: out.add(set(1 + r.nextInt(3), term(2))); break;
                case 5: case 6:
                    out.add(new Branch(bool(2), algo(depth - 1, loopDepth), algo(depth - 1, loopDepth)));
                    break;
                default:
                    if (loopDepth >= 3) { out.add(printT(term(1))); break; }
                    int c = 10 + loopDepth, bound = 1 + r.nextInt(4);
                    LoopKind kind = LoopKind.values()[r.nextInt(4)];
                    Bool_ keepGoing = lesser(n(c), num(bound));
                    Bool_ cond;
                    boolean untilKind = kind == LoopKind.UNTIL_DO || kind == LoopKind.DO_UNTIL;
                    if (!untilKind) cond = keepGoing;
                    else cond = r.nextBoolean() ? new Not(keepGoing) : larger(n(c), num(bound - 1));
                    Algo body = algo(depth - 1, loopDepth + 1);
                    body.instrs.add(incr(c));
                    out.add(set(c, num(0)));
                    out.add(new Loop(kind, cond, body));
            }
            return out;
        }

        Term term(int d) {
            int p = r.nextInt(d <= 0 ? 2 : 8);
            switch (p) {
                case 0: return n(1 + r.nextInt(3));
                case 1: return new Num(Integer.toString(r.nextInt(15) - 5));
                case 2: return add(term(d - 1), term(d - 1));
                case 3: return sub(term(d - 1), term(d - 1));
                case 4: return mul(term(d - 1), term(d - 1));
                case 5: return new Neg(term(d - 1));
                case 6: return mod(term(d - 1), num(2 + r.nextInt(6)));
                default: return n(1 + r.nextInt(3));
            }
        }

        Bool_ bool(int d) {
            int p = r.nextInt(d <= 0 ? 3 : 6);
            switch (p) {
                case 0: return eq(term(1), term(1));
                case 1: return larger(term(1), term(1));
                case 2: return lesser(term(1), term(1));
                case 3: return new Not(bool(d - 1));
                case 4: return new And(bool(d - 1), bool(d - 1));
                default: return new Or(bool(d - 1), bool(d - 1));
            }
        }
    }

    static final class Interp {
        static final class TooLong extends RuntimeException {}
        final Map<String, Integer> env = new HashMap<>();
        final StringBuilder out = new StringBuilder();
        int steps = 0;

        String run(Algo a) { exec(a); return out.toString(); }

        void exec(Algo a) { for (Instr i : a.instrs) exec(i); }

        void exec(Instr i) {
            if (++steps > 20000) throw new TooLong();
            if (i instanceof PrintString) {
                String s = ((PrintString) i).text;
                if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                out.append(s);
            } else if (i instanceof PrintTerm) out.append(eval(((PrintTerm) i).term));
            else if (i instanceof Assign) env.put(NAMES.get(((Assign) i).nodeId), eval(((Assign) i).term));
            else if (i instanceof Branch) {
                Branch b = (Branch) i;
                exec(eval(b.cond) ? b.thenA : b.elseA);
            } else if (i instanceof Loop) {
                Loop l = (Loop) i;
                switch (l.kind) {
                    case WHILE_DO: while (eval(l.cond)) { tick(); exec(l.body); } break;
                    case UNTIL_DO: while (!eval(l.cond)) { tick(); exec(l.body); } break;
                    case DO_WHILE: do { tick(); exec(l.body); } while (eval(l.cond)); break;
                    case DO_UNTIL: do { tick(); exec(l.body); } while (!eval(l.cond)); break;
                }
            }
        }
        void tick() { if (++steps > 20000) throw new TooLong(); }

        int eval(Term t) {
            if (t instanceof Num) return Integer.parseInt(((Num) t).value);
            if (t instanceof Name) return env.getOrDefault(NAMES.get(((Name) t).nodeId), 0);
            if (t instanceof Neg) return -eval(((Neg) t).inner);
            BinOp b = (BinOp) t;
            int l = eval(b.left), r = eval(b.right);
            switch (b.op) {
                case "add": return l + r;
                case "sub": return l - r;
                case "mul": return l * r;
                case "mod":
                    if (r == 0 || r == -1) throw new ArithmeticException();
                    return l % r;
                default: throw new IllegalStateException(b.op);
            }
        }

        boolean eval(Bool_ b) {
            if (b instanceof Not) return !eval(((Not) b).inner);
            if (b instanceof And) return eval(((And) b).left) && eval(((And) b).right);
            if (b instanceof Or)  return eval(((Or) b).left) || eval(((Or) b).right);
            Cmp c = (Cmp) b;
            int l = eval(c.left), r = eval(c.right);
            switch (c.op) {
                case "eq": return l == r;
                case "larger": return l > r;
                default: return l < r;
            }
        }
    }

    public static boolean gppAvailable() {
        try {
            Process p = new ProcessBuilder("g++", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    public static String compileAndRun(String body, String type, Collection<String> vars) throws Exception {
        StringBuilder src = new StringBuilder("#include <iostream>\nint main()\n{\n");
        for (String v : new TreeSet<>(vars)) src.append(type).append(' ').append(v).append(" = 0;\n");
        src.append(body).append("return 0;\n}\n");

        Path dir = Files.createTempDirectory("cg3a");
        try {
            Path cpp = dir.resolve("p.cpp"), exe = dir.resolve("p.exe");
            Files.write(cpp, src.toString().getBytes(StandardCharsets.UTF_8));
            Process c = new ProcessBuilder("g++", "-std=c++17", "-fwrapv", "-w", "-o", exe.toString(), cpp.toString())
                .redirectErrorStream(true).start();
            String cout = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!c.waitFor(60, TimeUnit.SECONDS)) { c.destroyForcibly(); throw new IOException("g++ timed out"); }
            if (c.exitValue() != 0) throw new IOException("g++ rejected the generated code:\n" + cout + "\n--- source ---\n" + src);
            Process r = new ProcessBuilder(exe.toString()).redirectErrorStream(true).start();
            String rout = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!r.waitFor(10, TimeUnit.SECONDS)) { r.destroyForcibly(); throw new IOException("program timed out (infinite loop?)"); }
            if (r.exitValue() != 0) throw new IOException("program exited with " + r.exitValue());
            return rout;
        } finally {
            File[] fs = dir.toFile().listFiles();
            if (fs != null) for (File f : fs) f.delete();
            dir.toFile().delete();
        }
    }

    static String abbreviate(String s) { return s.length() > 80 ? s.substring(0, 80) + "..." : s; }

    static void check(String name, String actual, String expected) {
        if (actual.equals(expected)) ok(name);
        else bad(name, "expected:\n" + expected + "\nactual:\n" + actual);
    }
    static void check(String name, boolean cond) { if (cond) ok(name); else bad(name, "condition false"); }

    interface Thrower { void run(); }
    static void expectThrows(String name, Class<? extends Throwable> cls, Thrower t) {
        try { t.run(); bad(name, "no exception, expected " + cls.getSimpleName()); }
        catch (Throwable e) {
            if (cls.isInstance(e)) ok(name);
            else bad(name, "got " + e + ", expected " + cls.getSimpleName());
        }
    }

    static void ok(String n) { System.out.println("✅ " + n); pass++; }
    static void bad(String n, String detail) {
        System.out.println("❌ " + n);
        System.out.println(detail);
        fail++;
    }
}
