package codegen;

import java.util.*;

public class CodeGen {

    // ─────────────────────────────── AST nodes ───────────────────────────────
    public static abstract class Node {}

    public static abstract class Instr extends Node {}
    public static class PrintString extends Instr { public final String text;
        public PrintString(String t) { text = t; } }
    public static class PrintTerm   extends Instr { public final Term term;
        public PrintTerm(Term t) { term = t; } }
    public static class Nop         extends Instr {}
    public static class Comment     extends Instr { public final String text;
        public Comment(String t) { text = t; } }
    public static class Assign      extends Instr { public final int nodeId; public final Term term;
        public Assign(int id, Term t) { nodeId = id; term = t; } }
    public static class Branch      extends Instr { public final Bool_ cond; public final Algo thenA, elseA;
        public Branch(Bool_ c, Algo t, Algo e) { cond = c; thenA = t; elseA = e; } }
    public static class Loop        extends Instr { public final LoopKind kind; public final Bool_ cond; public final Algo body;
        public Loop(LoopKind k, Bool_ c, Algo b) { kind = k; cond = c; body = b; } }

    public static abstract class Term extends Node {}
    public static class Name  extends Term { public final int nodeId;      public Name(int id)    { nodeId = id; } }
    public static class Num   extends Term { public final String value;    public Num(String v)   { value = v; } }
    public static class BinOp extends Term { public final String op; public final Term left, right;
        public BinOp(String o, Term l, Term r) { op = o; left = l; right = r; } }
    public static class Neg   extends Term { public final Term inner;      public Neg(Term i)     { inner = i; } }

    public static abstract class Bool_ extends Node {}
    public static class Not extends Bool_ { public final Bool_ inner;          public Not(Bool_ i) { inner = i; } }
    public static class Or  extends Bool_ { public final Bool_ left, right;    public Or(Bool_ l, Bool_ r) { left = l; right = r; } }
    public static class And extends Bool_ { public final Bool_ left, right;    public And(Bool_ l, Bool_ r) { left = l; right = r; } }
    public static class Cmp extends Bool_ { public final String op; public final Term left, right;
        public Cmp(String o, Term l, Term r) { op = o; left = l; right = r; } }

    public static class Algo extends Node { public final List<Instr> instrs = new ArrayList<>(); }

    public enum LoopKind { WHILE_DO, UNTIL_DO, DO_WHILE, DO_UNTIL }

    // ────────────────────────────── generator state ──────────────────────────────
    private StringBuilder out;
    private int tempCounter, labelCounter;
    private String type;
    private Map<Integer, String> names = new HashMap<>();

    public CodeGen() { this("int"); }
    public CodeGen(String type) { this.type = type; }

    public static String typeFor(boolean isFloat) { return isFloat ? "float" : "int"; }

    public void setNames(Map<Integer, String> m) { this.names = Objects.requireNonNull(m); }
    public void setType(String t) { this.type = t; }

    private String newTemp()  { return "v" + (++tempCounter); }
    private String newLabel() { return "L" + (++labelCounter); }

    private String lookup(int nodeId) {
        String s = names.get(nodeId);
        if (s == null) throw new IllegalStateException(
            "no symbol-table entry for NAME node id " + nodeId);
        return s;
    }

    private void emit(String line)  { out.append(line).append('\n'); }
    private void emitDecl(String v) { emit(type + " " + v + ";"); }

    private void endInstr() {
        int n = out.length();
        if (n > 0 && out.charAt(n - 1) == '\n') out.insert(n - 1, ';');
        else out.append(';');
    }

    private String capture(Runnable r) {
        StringBuilder saved = out;
        out = new StringBuilder();
        try { r.run(); return out.toString(); }
        finally { out = saved; }
    }

    // ───────────────────────────── public entry ─────────────────────────────
    public String generate(Algo algo) {
        out = new StringBuilder();
        tempCounter = 0;
        labelCounter = 0;
        transAlgo(algo);
        return out.toString();
    }

    // ───────────────────────────── ALGO ─────────────────────────────
    private void transAlgo(Algo algo) {
        for (Instr i : algo.instrs) transInstr(i);
        emit("/* end */");
    }

    // ───────────────────────────── INSTR ─────────────────────────────
    private void transInstr(Instr i) {
        if (i instanceof PrintString) {
            emit("std::cout << " + cString(((PrintString) i).text));
            endInstr();
        } else if (i instanceof PrintTerm) {
            String vN = newTemp();
            emitDecl(vN);
            transTerm(((PrintTerm) i).term, vN);
            emit("std::cout << " + vN);
            endInstr();
        } else if (i instanceof Nop) {
            emit("/* nop */");
            endInstr();
        } else if (i instanceof Comment) {
            emit("/* " + safeComment(((Comment) i).text) + " */");
            endInstr();
        } else if (i instanceof Assign) {
            Assign a = (Assign) i;
            String vN = newTemp();
            emitDecl(vN);
            transTerm(a.term, vN);
            emit(lookup(a.nodeId) + " = " + vN);
            endInstr();
        } else if (i instanceof Branch) {
            transBranch((Branch) i);
        } else if (i instanceof Loop) {
            transLoop((Loop) i);
        } else {
            throw new IllegalArgumentException("Unknown INSTR: " + i.getClass().getSimpleName());
        }
    }

    static String cString(String raw) {
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        s = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + s + "\"";
    }

    static String safeComment(String raw) { return raw.replace("*/", "* /"); }

    static String cNumber(String v) {
        if (v.indexOf('.') >= 0) return v;
        boolean neg = v.startsWith("-");
        String d = neg ? v.substring(1) : v;
        int k = 0;
        while (k < d.length() - 1 && d.charAt(k) == '0') k++;
        return (neg ? "-" : "") + d.substring(k);
    }

    // ───────────────────────────── TERM ─────────────────────────────
    private void transTerm(Term t, String result) {
        if (t instanceof Num) {
            emit(result + " = " + cNumber(((Num) t).value) + ";");
        } else if (t instanceof Name) {
            emit(result + " = " + lookup(((Name) t).nodeId) + ";");
        } else if (t instanceof BinOp) {
            BinOp b = (BinOp) t;
            String op = mapBinOp(b.op);
            String t1 = newTemp(), t2 = newTemp();
            emitDecl(t1);
            emitDecl(t2);
            transTerm(b.left,  t1);
            transTerm(b.right, t2);
            emit(result + " = " + t1 + " " + op + " " + t2 + ";");
        } else if (t instanceof Neg) {
            String t1 = newTemp();
            emitDecl(t1);
            transTerm(((Neg) t).inner, t1);
            emit(result + " = -" + t1 + ";");
        } else {
            throw new IllegalArgumentException("Unknown TERM: " + t.getClass().getSimpleName());
        }
    }

    private String mapBinOp(String op) {
        switch (op) {
            case "mod": return "%";
            case "div": return "/";
            case "add": return "+";
            case "sub": return "-";
            case "mul": return "*";
            default: throw new IllegalArgumentException("Unknown binop: " + op);
        }
    }

    // ───────────────────────────── BOOL ─────────────────────────────
    private void transBool(Bool_ b, String T, String F) {
        if (b instanceof Cmp) {
            Cmp c = (Cmp) b;
            String op = mapCmpOp(c.op);
            String t1 = newTemp(), t2 = newTemp();
            emitDecl(t1);
            emitDecl(t2);
            transTerm(c.left,  t1);
            transTerm(c.right, t2);
            emit("if (" + t1 + " " + op + " " + t2 + ") goto " + T + ";");
            emit("goto " + F + ";");
        } else if (b instanceof Not) {
            transBool(((Not) b).inner, F, T);
        } else if (b instanceof Or) {
            Or o = (Or) b;
            String L = newLabel();
            transBool(o.left,  T, L);
            emit(L + ":");
            transBool(o.right, T, F);
        } else if (b instanceof And) {
            And a = (And) b;
            String L = newLabel();
            transBool(a.left,  L, F);
            emit(L + ":");
            transBool(a.right, T, F);
        } else {
            throw new IllegalArgumentException("Unknown BOOL: " + b.getClass().getSimpleName());
        }
    }

    private String mapCmpOp(String op) {
        switch (op) {
            case "eq":     return "==";
            case "larger": return ">";
            case "lesser": return "<";
            default: throw new IllegalArgumentException("Unknown cmp: " + op);
        }
    }

    // ───────────────────────────── BRANCH ─────────────────────────────
    private void transBranch(Branch br) {
        String La = newLabel(), Lb = newLabel(), Lc = newLabel();
        String decision  = capture(() -> transBool(br.cond, La, Lb));
        String trueCode  = capture(() -> transAlgo(br.thenA));
        String falseCode = capture(() -> transAlgo(br.elseA));

        emit("/* if */");
        out.append(decision);
        emit(Lb + ": /* else */");
        out.append(falseCode);
        emit("goto " + Lc + ";");
        emit(La + ": /* then */");
        out.append(trueCode);
        emit(Lc + ": /* continue */");
        endInstr();
    }

    // ───────────────────────────── LOOP ─────────────────────────────
    private void transLoop(Loop loop) {
        switch (loop.kind) {
            case WHILE_DO:
            case UNTIL_DO: {
                String ls = newLabel(), le = newLabel(), d = newLabel();
                boolean isUntil = loop.kind == LoopKind.UNTIL_DO;
                String decision = capture(() -> {
                    if (isUntil) transBool(loop.cond, le, d);
                    else         transBool(loop.cond, d, le);
                });
                String body = capture(() -> transAlgo(loop.body));
                emit(ls + ": /* until */");
                out.append(decision);
                emit(d + ": /* do */");
                out.append(body);
                emit("goto " + ls + ";");
                emit(le + ":");
                emit("/* exit */");
                endInstr();
                break;
            }
            case DO_WHILE:
            case DO_UNTIL: {
                String ld = newLabel(), le = newLabel();
                boolean isUntil = loop.kind == LoopKind.DO_UNTIL;
                String decision = capture(() -> {
                    if (isUntil) transBool(loop.cond, le, ld);
                    else         transBool(loop.cond, ld, le);
                });
                String body = capture(() -> transAlgo(loop.body));
                emit(ld + ": /* do */");
                out.append(body);
                emit("/* while */");
                out.append(decision);
                emit(le + ": /* exit */");
                endInstr();
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown loop kind " + loop.kind);
        }
    }
}
