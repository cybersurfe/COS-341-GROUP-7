import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/*
reference interpreter for SPL, used ONLY by the tests (differential testing against the compiled C++).
deliberately independent of the compiler: it does not use the symbol table or the renaming of phase 2a.
it scopes the way a textbook interpreter does: one run-time frame per function call, a static link to the frame
in which the function was declared, nearest declaration wins.

it mimics the target semantics exactly:
  int programs : 32-bit int, any overflow / mod by zero => Skip (undefined behaviour in C, not a valid test)
  float programs: IEEE float32 after every operation, printing like std::cout (%g, 6 significant digits),
                 inf / nan => Skip
  and / or short-circuit (the generated jumping code does too), terms are evaluated left to right
  all variables start at 0
*/
public final class RefInterp {

    /** the test is not usable (undefined behaviour in C, or too long running). not a compiler bug. */
    public static final class Skip extends RuntimeException {
        public Skip(String why) { super(why, null, false, false); }
    }

    private static final class Frame {
        final Frame link;
        final Map<String, Double> vars = new HashMap<>();
        final Map<String, Parser.Node> funcs = new HashMap<>();   //name -> F_TYPE node (declared in THIS frame)
        Frame(Frame link) { this.link = link; }
    }

    private final boolean isFloat;
    private final StringBuilder out = new StringBuilder();
    private long steps = 0;
    private final long maxSteps;

    private RefInterp(boolean isFloat, long maxSteps) {
        this.isFloat = isFloat;
        this.maxSteps = maxSteps;
    }

    /** runs the program; returns everything it prints. */
    public static String run(Parser.Node root, boolean isFloat, long maxSteps) {
        RefInterp in = new RefInterp(isFloat, maxSteps);
        Frame main = new Frame(null);
        in.enterP(child(root, "P"), main);
        in.algo(child(child(root, "P"), "ALGO"), main);
        return in.out.toString();
    }

    private void tick() {
        if (++steps > maxSteps) throw new Skip("step budget exceeded");
    }

    //declares the variables and functions of a P in the given frame (params were put there by the caller)
    private void enterP(Parser.Node p, Frame f) {
        for (Parser.Node x = child(p, "V_DECL"); x != null; x = childOrNull(x, "V_DECL"))
            for (Parser.Node c : x.children) if (isName(c)) f.vars.put(c.contents, 0.0);
        for (Parser.Node x = child(p, "F_DECL"); x != null; x = childOrNull(x, "F_DECL")) {
            Parser.Node ft = childOrNull(x, "F_TYPE");
            if (ft != null) f.funcs.put(ft.children.get(1).contents, ft);
        }
    }

    private void algo(Parser.Node a, Frame f) {
        for (Parser.Node x = a; x != null; x = childOrNull(x, "ALGO")) {
            Parser.Node instr = childOrNull(x, "INSTR");
            if (instr != null) instr(instr, f);
        }
    }

    private void instr(Parser.Node in, Frame f) {
        tick();
        Parser.Node first = in.children.get(0);
        if (first.isLeaf) {
            switch (first.contents) {
                case "print": {
                    Parser.Node o = in.children.get(1);
                    if (o.children.size() == 1) {
                        String s = o.children.get(0).contents;
                        out.append(s, 1, s.length() - 1);
                    } else {
                        out.append(show(term(child(o, "TERM"), f)));
                    }
                    return;
                }
                default: return;   //nop, comment
            }
        }
        switch (first.contents) {
            case "ASSIGN": {
                double v = term(child(first, "TERM"), f);
                assign(first.children.get(0).contents, v, f);
                return;
            }
            case "BRANCH": {
                boolean b = bool(child(first, "BOOL"), f);
                java.util.List<Parser.Node> algos = children(first, "ALGO");
                algo(b ? algos.get(0) : algos.get(1), f);
                return;
            }
            case "LOOP": loop(first, f); return;
            case "CALL": call(first, f); return;
            default: throw new IllegalStateException(first.contents);
        }
    }

    private void loop(Parser.Node l, Frame f) {
        boolean until = child(l, "COND").children.get(0).contents.equals("until");
        Parser.Node bool = child(l, "BOOL"), body = child(l, "ALGO");
        boolean testFirst = !l.children.get(0).isLeaf;
        if (testFirst) {
            while (true) {
                tick();
                boolean b = bool(bool, f);
                if (until ? b : !b) break;   //until: leave when true. while: leave when false
                algo(body, f);
            }
        } else {
            do {
                tick();
                algo(body, f);
                boolean b = bool(bool, f);
                if (until ? b : !b) break;   //do-until stops when true, do-while stops when false
            } while (true);
        }
    }

    private boolean bool(Parser.Node b, Frame f) {
        String op = b.children.get(0).contents;
        switch (op) {
            case "not": return !bool(b.children.get(2), f);
            case "and": return bool(b.children.get(2), f) && bool(b.children.get(3), f);
            case "or":  return bool(b.children.get(2), f) || bool(b.children.get(3), f);
            default: {
                double x = term(b.children.get(2), f);
                double y = term(b.children.get(3), f);
                return op.equals("eq") ? x == y : op.equals("larger") ? x > y : x < y;
            }
        }
    }

    private double term(Parser.Node t, Frame f) {
        tick();
        Parser.Node first = t.children.get(0);
        if (!first.isLeaf) return call(first, f);
        String c = first.contents;
        if (isName(first)) return lookup(c, f);
        switch (c) {
            case "add": case "sub": case "mul": case "div": case "mod": {
                double x = term(t.children.get(2), f);
                double y = term(t.children.get(3), f);
                return arith(c, x, y);
            }
            case "neg": return arith("neg", term(t.children.get(2), f), 0);
            default: return literal(c);
        }
    }

    private double literal(String s) {
        if (isFloat) return (float) Double.parseDouble(s);   //decimal -> double -> float, like the C++ compiler does
        long v = Long.parseLong(s);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new Skip("literal out of int range");
        return v;
    }

    private double arith(String op, double x, double y) {
        if (isFloat) {
            float a = (float) x, b = (float) y, r;
            switch (op) {
                case "add": r = a + b; break;
                case "sub": r = a - b; break;
                case "mul": r = a * b; break;
                case "div": r = a / b; break;
                case "neg": r = -a; break;
                default: throw new Skip("mod in a float program");
            }
            if (Float.isNaN(r) || Float.isInfinite(r)) throw new Skip("non-finite float");
            return r;
        }
        long a = (long) x, b = (long) y, r;
        switch (op) {
            case "add": r = a + b; break;
            case "sub": r = a - b; break;
            case "mul": r = a * b; break;
            case "neg": r = -a; break;
            case "mod": if (b == 0) throw new Skip("mod by zero"); r = a % b; break;   //truncating, like C
            default: throw new Skip("div in an int program");
        }
        if (r < Integer.MIN_VALUE || r > Integer.MAX_VALUE) throw new Skip("int overflow");
        return r;
    }

    private double lookup(String name, Frame f) {
        for (Frame x = f; x != null; x = x.link) {
            Double v = x.vars.get(name);
            if (v != null) return v;
        }
        throw new IllegalStateException("undeclared " + name + " (the generator produced an invalid program)");
    }

    private void assign(String name, double v, Frame f) {
        for (Frame x = f; x != null; x = x.link) {
            if (x.vars.containsKey(name)) { x.vars.put(name, v); return; }
        }
        throw new IllegalStateException("undeclared " + name);
    }

    //a call: new frame whose static link is the frame that declares the function (= the current frame, see 2a rule F1)
    private double call(Parser.Node call, Frame f) {
        tick();
        String name = call.children.get(0).contents;
        Parser.Node ft = f.funcs.get(name);
        if (ft == null) throw new IllegalStateException("undeclared function " + name);
        java.util.List<Double> args = new java.util.ArrayList<>();
        for (Parser.Node x = child(call, "INPUT"); x != null; x = childOrNull(x, "INPUT")) {
            Parser.Node t = childOrNull(x, "TERM");
            if (t != null) args.add(term(t, f));
        }
        Frame nf = new Frame(f);
        int i = 0;
        for (Parser.Node x = child(ft, "V_DECL"); x != null; x = childOrNull(x, "V_DECL"))
            for (Parser.Node c : x.children) if (isName(c)) nf.vars.put(c.contents, args.get(i++));
        Parser.Node body = child(ft, "P");
        enterP(body, nf);
        algo(child(body, "ALGO"), nf);
        boolean isNum = ft.children.get(0).contents.equals("num");
        return isNum ? term(child(ft, "TERM"), nf) : 0;
    }

    //printing exactly like std::cout does
    private String show(double v) {
        if (!isFloat) return Long.toString((long) v);
        return fmtG((float) v);
    }

    /** std::cout << float : printf("%g") with 6 significant digits. */
    static String fmtG(float fv) {
        double d = fv;
        if (d == 0) return (1 / d < 0) ? "-0" : "0";
        BigDecimal bd = new BigDecimal(d).round(new MathContext(6, RoundingMode.HALF_EVEN));
        int exp = bd.precision() - bd.scale() - 1;   //decimal exponent of the leading digit
        if (exp < -4 || exp >= 6) {
            String digits = bd.unscaledValue().abs().toString().replaceAll("0+$", "");
            if (digits.isEmpty()) digits = "0";
            String mant = digits.substring(0, 1) + (digits.length() > 1 ? "." + digits.substring(1) : "");
            String e = Integer.toString(Math.abs(exp));
            if (e.length() < 2) e = "0" + e;
            return (d < 0 ? "-" : "") + mant + "e" + (exp < 0 ? "-" : "+") + e;
        }
        return bd.stripTrailingZeros().toPlainString();
    }

    private static boolean isName(Parser.Node n) { return n.isLeaf && n.contents.startsWith("#"); }
    private static Parser.Node childOrNull(Parser.Node n, String contents) {
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) return c;
        return null;
    }
    private static Parser.Node child(Parser.Node n, String contents) {
        Parser.Node c = childOrNull(n, contents);
        if (c == null) throw new IllegalStateException("no " + contents + " in " + n.contents);
        return c;
    }
    private static java.util.List<Parser.Node> children(Parser.Node n, String contents) {
        java.util.List<Parser.Node> out = new java.util.ArrayList<>();
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) out.add(c);
        return out;
    }
}
