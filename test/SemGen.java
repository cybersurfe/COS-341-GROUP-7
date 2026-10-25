import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/*
random generator of SEMANTICALLY VALID SPL programs (test tool, unlike ProgramGen which only guarantees syntax).
what it deliberately exercises:
  * scoping: the same few names are reused at many levels so shadowing, outer-variable access from nested
    functions, and "same name in sibling functions" all happen all the time
  * functions: void and num, 0..3 parameters, nested up to depth 3, calls only to functions of the SAME P (the 2a rule)
  * every instruction, every loop form (while / until / do-while / do-until), every boolean operator
  * numbers: the program picks ONE mode, so it is valid under the 2b rules:
      INT (no mod/div/dot), MOD (mod, ints only), DIV (div and decimals), DEC (decimals, no div)
termination: every P owns a few loop-counter variables that nothing else may assign, and loops count them up.
a small fraction of "wild" loops has no such guarantee; the interpreter's step budget then skips the test.
*/
public final class SemGen {
    private enum Mode { INT, MOD, DIV, DEC }

    private static final String[] VARS = {"#a", "#b", "#c", "#x", "#y", "#z"};
    private static final String[] FUNCS = {"#f", "#g", "#h", "#p", "#q"};
    private static final String[] INTS = {"0", "1", "2", "3", "5", "7", "10", "-1", "-4", "12", "100", "9", "-30"};
    private static final String[] DECS = {"0.5", "1.5", "2.25", "-0.5", "3.75", "0.1", "12.5", "-7.125", "0.3", "100.25"};
    private static final String[] STRS = {"\"hello\"", "\"a-b\"", "\"x,y:z.\"", "\"ok!\"", "\"?\"", "\"\"", "\"0\"", "\"line-1.\""};

    private static final class Fn {
        final String name; final int nparams; final boolean isNum; final int cost;   //cost = number of inlined bodies one call expands to
        Fn(String name, int nparams, boolean isNum, int cost) { this.name = name; this.nparams = nparams; this.isNum = isNum; this.cost = cost; }
    }

    private static final class Scope {
        final Set<String> visible = new LinkedHashSet<>();     //every variable name usable here
        final List<String> assignable = new ArrayList<>();     //visible, minus loop counters
        final List<String> counters = new ArrayList<>();       //this P's own loop counters
        final List<Fn> funcs = new ArrayList<>();              //functions callable from here (this P's F_DECL)
    }

    private static final class PGen {
        String text;
        String ret;      //return term of a num function body (generated inside the body's own scope), else null
        int cost;        //1 + the cost of every call inside this body
    }

    private final Random r;
    private Mode mode;
    private int ctrId = 0;
    private int cost = 0;   //inlined bodies caused so far by the P that is currently being generated
    private static final int MAX_COST = 24;   //keeps the generated C++ a few KB instead of megabytes

    private SemGen(long seed) {
        r = new Random(seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);   //consecutive seeds give correlated first draws otherwise
        for (int i = 0; i < 8; i++) r.nextInt();
    }

    public static String generate(long seed) {
        SemGen g = new SemGen(seed);
        g.mode = Mode.values()[g.r.nextInt(4)];
        return g.genP(3, new Scope(), new ArrayList<>(), 6, false).text;
    }

    //P -> V_DECL : F_DECL : ALGO.   parent holds the visible names of the enclosing scopes
    private PGen genP(int depth, Scope parent, List<String> params, int budget, boolean wantsReturn) {
        int savedCost = cost;
        cost = 0;
        Scope sc = new Scope();
        sc.visible.addAll(parent.visible);
        sc.assignable.addAll(parent.assignable);
        sc.visible.addAll(params);
        sc.assignable.addAll(params);

        List<String> locals = new ArrayList<>();
        int nl = r.nextInt(4);
        for (int i = 0; i < nl; i++) {
            String v = VARS[r.nextInt(VARS.length)];
            if (!locals.contains(v) && !params.contains(v)) locals.add(v);
        }
        for (String v : locals) { sc.visible.add(v); if (!sc.assignable.contains(v)) sc.assignable.add(v); }
        for (int i = 0; i < 3; i++) {
            String c = "#k" + (++ctrId);
            sc.counters.add(c);
            sc.visible.add(c);
        }

        StringBuilder fdecl = new StringBuilder();
        if (depth > 0) {
            int nf = depth == 3 ? 1 + r.nextInt(3) : r.nextInt(3);   //the main program nearly always has functions
            List<String> used = new ArrayList<>();
            for (int i = 0; i < nf; i++) {
                String name = FUNCS[r.nextInt(FUNCS.length)];
                if (used.contains(name)) continue;
                used.add(name);
                int np = r.nextInt(4);
                List<String> ps = new ArrayList<>();
                for (int k = 0; k < np; k++) {
                    String v = VARS[r.nextInt(VARS.length)];
                    if (!ps.contains(v)) ps.add(v);
                }
                boolean isNum = r.nextBoolean();
                PGen body = genP(depth - 1, sc, ps, 4, isNum);
                fdecl.append(isNum ? "num " : "void ").append(name).append(" ( ");
                for (String p : ps) fdecl.append(p).append(' ');
                fdecl.append(") { ").append(body.text).append(" return");
                if (isNum) fdecl.append(" ( ").append(body.ret).append(" )");
                fdecl.append(" } ");
                sc.funcs.add(new Fn(name, ps.size(), isNum, body.cost));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String v : locals) sb.append(v).append(' ');
        for (String c : sc.counters) sb.append(c).append(' ');
        sb.append(": ").append(fdecl).append(": ");
        sb.append(genAlgo(sc, depth, budget, 0));
        //dump the P's own variables at its end: makes every side effect (also those on outer variables) visible in the output
        for (String v : params) sb.append("print ( ").append(v).append(" ) ; print \",\" ; ");
        for (String v : locals) sb.append("print ( ").append(v).append(" ) ; print \",\" ; ");

        PGen out = new PGen();
        out.text = sb.toString();
        if (wantsReturn) out.ret = genTerm(sc, 2);   //inside this P's own scope: its calls count towards this body
        out.cost = 1 + cost;
        cost = savedCost;
        return out;
    }

    //ALGO: a list of "INSTR ;"
    private String genAlgo(Scope sc, int depth, int budget, int loopLevel) {
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(budget + 1);
        for (int i = 0; i < n; i++) sb.append(genInstr(sc, depth, loopLevel)).append(" ; ");
        return sb.toString();
    }

    private String genInstr(Scope sc, int depth, int loopLevel) {
        int k = r.nextInt(100);
        List<Fn> voids = new ArrayList<>();
        for (Fn f : sc.funcs) if (!f.isNum && cost + f.cost <= MAX_COST) voids.add(f);
        if (k < 14) return "print ( " + genTerm(sc, 2) + " )";
        if (k < 19) return "print " + STRS[r.nextInt(STRS.length)];
        if (k < 21) return "nop";
        if (k < 23) return "comment " + STRS[r.nextInt(STRS.length)];
        if (k < 45 && !sc.assignable.isEmpty())
            return sc.assignable.get(r.nextInt(sc.assignable.size())) + " = " + genTerm(sc, 3);
        if (k < 65 && !voids.isEmpty()) return genCall(sc, voids.get(r.nextInt(voids.size())));
        if (k < 82) {
            if (depth >= 0)
                return "if " + genBool(sc, 2) + " then { " + genAlgo(sc, depth - 1, 3, loopLevel)
                     + "} else { " + genAlgo(sc, depth - 1, 3, loopLevel) + "}";
            return "print ( " + genTerm(sc, 1) + " )";
        }
        if (k < 96) return loopLevel < sc.counters.size() ? genLoop(sc, depth, loopLevel) : "nop";
        if (depth >= 0) {   //"wild" loop, may not terminate (the interpreter's step budget will skip those)
            String body = genAlgo(sc, depth - 1, 3, loopLevel);
            switch (r.nextInt(3)) {
                case 0: return "while " + genBool(sc, 1) + " do { " + body + "}";
                case 1: return "until " + genBool(sc, 1) + " do { " + body + "}";
                default: return "do { " + body + "} while " + genBool(sc, 1);
            }
        }
        return "nop";
    }

    //the four loop forms, each driven by this P's counter number loopLevel (so nested loops never share a counter)
    private String genLoop(Scope sc, int depth, int loopLevel) {
        String c = sc.counters.get(loopLevel);
        int K = r.nextInt(5);
        String body = genAlgo(sc, depth - 1, 3, loopLevel + 1);
        String inc = c + " = add ( " + c + " 1 ) ; ";
        String extra = r.nextInt(4) == 0 ? genBool(sc, 1) : null;
        switch (r.nextInt(4)) {
            case 0: {
                String cond = "lesser ( " + c + " " + K + " )";
                if (extra != null) cond = "and ( " + cond + " " + extra + " )";
                return c + " = 0 ; while " + cond + " do { " + body + inc + "}";
            }
            case 1: return c + " = 0 ; until eq ( " + c + " " + K + " ) do { " + body + inc + "}";
            case 2: {
                String cond = "lesser ( " + c + " " + K + " )";
                if (extra != null) cond = "and ( " + cond + " " + extra + " )";
                return c + " = 0 ; do { " + body + inc + "} while " + cond;
            }
            default: return c + " = 0 ; do { " + body + inc + "} until eq ( " + c + " " + (K + 1) + " )";
        }
    }

    private String genCall(Scope sc, Fn f) {
        cost += f.cost;
        StringBuilder sb = new StringBuilder(f.name).append(" ( ");
        for (int i = 0; i < f.nparams; i++) sb.append(genTerm(sc, 2)).append(' ');
        return sb.append(")").toString();
    }

    private String genBool(Scope sc, int d) {
        int k = r.nextInt(100);
        if (d <= 0 || k < 45) {
            String[] ops = {"eq", "larger", "lesser"};
            return ops[r.nextInt(3)] + " ( " + genTerm(sc, 1) + " " + genTerm(sc, 1) + " )";
        }
        if (k < 62) return "not ( " + genBool(sc, d - 1) + " )";
        if (k < 82) return "and ( " + genBool(sc, d - 1) + " " + genBool(sc, d - 1) + " )";
        return "or ( " + genBool(sc, d - 1) + " " + genBool(sc, d - 1) + " )";
    }

    private String lit() {
        boolean dec = (mode == Mode.DIV || mode == Mode.DEC) && r.nextInt(3) == 0;
        return dec ? DECS[r.nextInt(DECS.length)] : INTS[r.nextInt(INTS.length)];
    }

    private String genTerm(Scope sc, int d) {
        List<Fn> nums = new ArrayList<>();
        for (Fn f : sc.funcs) if (f.isNum && cost + f.cost <= MAX_COST) nums.add(f);
        int k = r.nextInt(100);
        if (d <= 0 || k < 30) {
            if (r.nextBoolean() && !sc.visible.isEmpty()) return pickVar(sc);
            return lit();
        }
        if (k < 50 && !nums.isEmpty()) return genCall(sc, nums.get(r.nextInt(nums.size())));
        if (k < 46) return "neg ( " + genTerm(sc, d - 1) + " )";
        if (k < 56 && mode == Mode.MOD) return "mod ( " + genTerm(sc, d - 1) + " " + INTS[1 + r.nextInt(5)] + " )";
        if (k < 66 && mode == Mode.DIV) {
            String[] divisors = {"2", "4", "0.5", "3", "-2", "8", "1.5"};
            return "div ( " + genTerm(sc, d - 1) + " " + divisors[r.nextInt(divisors.length)] + " )";
        }
        if (k < 72) return "mul ( " + genTerm(sc, d - 1) + " " + INTS[r.nextInt(8)] + " )";   //small factor keeps ints in range
        String op = r.nextBoolean() ? "add" : "sub";
        return op + " ( " + genTerm(sc, d - 1) + " " + genTerm(sc, d - 1) + " )";
    }

    private String pickVar(Scope sc) {
        List<String> l = new ArrayList<>(sc.visible);
        return l.get(r.nextInt(l.size()));
    }
}
