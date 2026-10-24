import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 2b: type analysis.
 *
 * Two whole-tree rules run first, exactly as the spec dictates:
 *   (A) if the tree contains both a 'mod' node and a 'div' node, reject with
 *       "A float-integer-conflict might perhaps be possible".
 *   (B) if the tree contains a 'mod' node, no NUM leaf may contain a decimal dot.
 *
 * Then each subtree is typed as one of ok / numeric / boolean / procedure.
 * 'unknown' is never returned: any place where we'd otherwise be stuck throws.
 *
 * Finally, isFloat() = hasDiv || hasDecimalNum (hasMod already forces both off).
 *
 * Depends on Diya's SemanticAnalyzer.SymbolTable ONLY for the mapping
 *   NAME leaf id -> Symbol (to know the sys name of each declared function).
 * Does NOT modify SymbolTable; all results are returned in Result.
 */
public final class TypeAnalyzer {

    public static final class TypeException extends RuntimeException {
        public TypeException(String msg) { super(msg); }
    }

    /** Everything downstream phases need, keyed off 3a/3b-friendly ids. */
    public static final class Result {
        public final boolean isFloat;    // THE flag the spec asks us to expose
        public final boolean hasMod;
        public final boolean hasDiv;
        public final boolean hasDecimalNum;
        /** sysName of each function -> "num" | "void". Populate SymbolTable.setType from this in the pipeline. */
        public final Map<String, String> functionTypes;
        /** tree node id of each CALL -> "num" | "void". Handy for 3b's per-CALL dispatch. */
        public final Map<Integer, String> callTypes;

        Result(boolean isFloat, boolean hasMod, boolean hasDiv, boolean hasDecimalNum,
               Map<String, String> functionTypes, Map<Integer, String> callTypes) {
            this.isFloat = isFloat;
            this.hasMod = hasMod;
            this.hasDiv = hasDiv;
            this.hasDecimalNum = hasDecimalNum;
            this.functionTypes = Collections.unmodifiableMap(functionTypes);
            this.callTypes = Collections.unmodifiableMap(callTypes);
        }
    }

    static final String MOD_DIV_MSG =
        "A float-integer-conflict might perhaps be possible";
    static final String MOD_DECIMAL_MSG =
        "a program with 'mod' may not contain a number with a decimal point";

    private Parser.Node firstMod, firstDiv, firstDecimalNum;
    private Map<String, String> functionTypes;
    private Map<Integer, String> callTypes;
    private SemanticAnalyzer.SymbolTable symbols;

    private TypeAnalyzer() {}

    public static Result analyze(Parser.Node root, SemanticAnalyzer.SymbolTable symbols) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(symbols, "symbols");
        TypeAnalyzer a = new TypeAnalyzer();
        a.symbols = symbols;
        a.firstPass(root);
        a.functionTypes = a.collectFunctionTypes(root);
        a.callTypes = new LinkedHashMap<>();
        a.secondPass(root);
        boolean isFloat = (a.firstDiv != null) || (a.firstDecimalNum != null);
        return new Result(isFloat, a.firstMod != null, a.firstDiv != null,
                a.firstDecimalNum != null, a.functionTypes, a.callTypes);
    }

    // ============================================================
    // Pass 1: mod/div/decimal (spec rule A then B, in that order)
    // ============================================================

    private void firstPass(Parser.Node root) {
        scanForFirsts(root);
        if (firstMod != null && firstDiv != null) {
            throw new TypeException("Semantic error at " + loc(firstMod) + ": " + MOD_DIV_MSG + ".");
        }
        if (firstMod != null && firstDecimalNum != null) {
            throw new TypeException("Semantic error at " + loc(firstDecimalNum) + ": "
                + MOD_DECIMAL_MSG + " ('" + firstDecimalNum.contents + "').");
        }
    }

    private void scanForFirsts(Parser.Node n) {
        if (n.isLeaf) {
            if (firstDecimalNum == null && isDecimalNumLeaf(n)) firstDecimalNum = n;
            return;
        }
        if (n.contents.equals("TERM")) {
            if (firstMod == null && firstOpIs(n, "mod")) firstMod = n;
            if (firstDiv == null && firstOpIs(n, "div")) firstDiv = n;
        }
        for (Parser.Node c : n.children) scanForFirsts(c);
    }

    private static boolean firstOpIs(Parser.Node term, String op) {
        if (term.children.isEmpty()) return false;
        Parser.Node f = term.children.get(0);
        return f.isLeaf && f.contents.equals(op);
    }

    private static boolean isDecimalNumLeaf(Parser.Node n) {
        if (!n.isLeaf) return false;
        String s = n.contents;
        if (s.isEmpty() || !s.contains(".")) return false;
        char c = s.charAt(0);
        if (c == '-' && s.length() >= 2) c = s.charAt(1);
        return Character.isDigit(c);
    }

    // ============================================================
    // Function types (F_TYPE kind + Diya's name resolution)
    // ============================================================

    private Map<String, String> collectFunctionTypes(Parser.Node root) {
        Map<String, String> out = new HashMap<>();
        collectFns(root, out);
        return out;
    }

    private void collectFns(Parser.Node n, Map<String, String> out) {
        if (!n.isLeaf && n.contents.equals("F_TYPE")) {
            String kind = n.children.get(0).contents;   // "void" or "num"
            Parser.Node nameLeaf = n.children.get(1);   // NAME leaf
            SemanticAnalyzer.Symbol s = symbols.byNode.get(nameLeaf.id);
            if (s == null) throw new IllegalStateException(
                "internal: F_TYPE name leaf id=" + nameLeaf.id
                + " has no symbol; run SemanticAnalyzer first");
            out.put(s.sysName, kind);
        }
        for (Parser.Node c : n.children) collectFns(c, out);
    }

    // ============================================================
    // Pass 2: type check each subtree
    // ============================================================

    private void secondPass(Parser.Node root) {
        for (Parser.Node c : root.children) {
            if (!c.isLeaf && c.contents.equals("P")) checkP(c);
        }
    }

    private void checkP(Parser.Node p) {
        for (Parser.Node c : p.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("F_DECL")) checkFDecl(c);
            else if (c.contents.equals("ALGO")) checkAlgo(c);
        }
    }

    private void checkFDecl(Parser.Node fdecl) {
        for (Parser.Node c : fdecl.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("F_TYPE")) checkFType(c);
            else if (c.contents.equals("F_DECL")) checkFDecl(c);
        }
    }

    private void checkFType(Parser.Node ftype) {
        Parser.Node retTerm = null, body = null;
        for (Parser.Node c : ftype.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("TERM")) retTerm = c;
            else if (c.contents.equals("P")) body = c;
        }
        if (body != null) checkP(body);
        if (retTerm != null) requireNumeric(retTerm, "num-function return value");
    }

    private void checkAlgo(Parser.Node algo) {
        for (Parser.Node c : algo.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("INSTR")) checkInstr(c);
        }
    }

    private void checkInstr(Parser.Node instr) {
        for (Parser.Node c : instr.children) {
            if (c.isLeaf) continue;
            switch (c.contents) {
                case "ASSIGN": {
                    Parser.Node term = null;
                    for (Parser.Node x : c.children)
                        if (!x.isLeaf && x.contents.equals("TERM")) term = x;
                    if (term != null) requireNumeric(term, "assignment right-hand side");
                    break;
                }
                case "OUTP":
                    for (Parser.Node x : c.children)
                        if (!x.isLeaf && x.contents.equals("TERM"))
                            requireNumeric(x, "print argument");
                    break;
                case "BRANCH":
                    for (Parser.Node x : c.children) {
                        if (x.isLeaf) continue;
                        if (x.contents.equals("BOOL")) requireBoolean(x, "if condition");
                        else if (x.contents.equals("ALGO")) checkAlgo(x);
                    }
                    break;
                case "LOOP":
                    for (Parser.Node x : c.children) {
                        if (x.isLeaf) continue;
                        if (x.contents.equals("BOOL")) requireBoolean(x, "loop condition");
                        else if (x.contents.equals("ALGO")) checkAlgo(x);
                    }
                    break;
                case "CALL":
                    // INSTR -> CALL: allowed for both void and num (result discarded).
                    checkCall(c, /*requireNumericResult=*/false, "function call as instruction");
                    break;
                default: break;
            }
        }
    }

    private void requireNumeric(Parser.Node term, String where) {
        Parser.Node first = term.children.get(0);
        if (!first.isLeaf) {
            // TERM -> CALL is the only non-leaf first child possible
            checkCall(first, /*requireNumericResult=*/true, where);
            return;
        }
        String s = first.contents;
        if (isOp(s)) {
            for (Parser.Node sub : subtermsOf(term))
                requireNumeric(sub, "'" + s + "' argument");
        }
        // NAME / NUM leaf => numeric, nothing to check
    }

    private void requireBoolean(Parser.Node bool, String where) {
        Parser.Node first = bool.children.get(0);
        String op = first.contents;
        if (op.equals("not")) {
            requireBoolean(findInner(bool, "BOOL"), "'not' operand");
            return;
        }
        if (op.equals("and") || op.equals("or")) {
            for (Parser.Node b : findInners(bool, "BOOL"))
                requireBoolean(b, "'" + op + "' operand");
            return;
        }
        // eq / larger / lesser -> operand TERMs must be numeric
        for (Parser.Node t : subtermsOf(bool))
            requireNumeric(t, "'" + op + "' operand");
    }

    private void checkCall(Parser.Node call, boolean requireNumericResult, String where) {
        Parser.Node nameLeaf = call.children.get(0);
        SemanticAnalyzer.Symbol s = symbols.byNode.get(nameLeaf.id);
        if (s == null) throw new IllegalStateException(
            "internal: CALL name leaf id=" + nameLeaf.id + " has no symbol");
        String type = functionTypes.get(s.sysName);
        if (type == null) throw new IllegalStateException(
            "internal: no recorded function type for " + s.sysName);

        callTypes.put(call.id, type);

        for (Parser.Node c : call.children) {
            if (!c.isLeaf && c.contents.equals("INPUT")) {
                for (Parser.Node t : subtermsOf(c))
                    requireNumeric(t, "call argument");
            }
        }
        if (requireNumericResult && type.equals("void")) {
            throw new TypeException("Semantic error at " + loc(call)
                + ": procedure (void function) used where a number was expected (" + where + ").");
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static List<Parser.Node> subtermsOf(Parser.Node n) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node c : n.children)
            if (!c.isLeaf && c.contents.equals("TERM")) out.add(c);
        return out;
    }

    private static Parser.Node findInner(Parser.Node n, String kind) {
        for (Parser.Node c : n.children)
            if (!c.isLeaf && c.contents.equals(kind)) return c;
        throw new IllegalStateException(
            "malformed tree: <" + n.contents + "> has no <" + kind + "> child");
    }

    private static List<Parser.Node> findInners(Parser.Node n, String kind) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node c : n.children)
            if (!c.isLeaf && c.contents.equals(kind)) out.add(c);
        return out;
    }

    private static boolean isOp(String s) {
        return s.equals("mod") || s.equals("add") || s.equals("sub") || s.equals("mul")
            || s.equals("div") || s.equals("neg");
    }

    private static String loc(Parser.Node n) {
        Parser.Node leaf = firstLeaf(n);
        return leaf == null ? "an unknown position"
            : "line " + leaf.line + ", column " + leaf.col;
    }

    private static Parser.Node firstLeaf(Parser.Node n) {
        if (n.isLeaf) return n;
        for (Parser.Node c : n.children) {
            Parser.Node l = firstLeaf(c);
            if (l != null) return l;
        }
        return null;
    }
}