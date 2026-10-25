import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
Phase 2b: type analysis.
input : the syntax tree (Parser) + the symbol table from phase 2a (every name leaf already has its sys name)
output: either a TypeException, or a Result: a type for every symbol and for every tree node, plus the one
        global decision the code generator needs: is the whole program int or float?

the rules are the ones of the 2b sheet, implemented 1:1 as a bottom-up tree crawl.
every function returns the type of its node ("ok", "numeric", "boolean", "procedure") or throws.

whole-tree rules (done first, before any finer analysis, as the sheet demands):
  W1. a tree with a mod node AND a div node is rejected outright:
      "A float-integer-conflict might perhaps be possible"
  W2. a tree with a mod node may not contain a NUM with a decimal dot.
the program is float if it has a div node or a NUM with a dot, otherwise int. (mod forces int, W1+W2 make that consistent.)

two extra checks that the sheet does not spell out but that code generation cannot live without:
  X1. a call must have exactly as many arguments as the function has parameters (inlining pairs them up).
  X2. in an int program every NUM must fit into a C int, otherwise the generated C would silently wrap.
*/
public final class TypeAnalyzer {

    public static final class TypeException extends RuntimeException {
        public TypeException(String msg) { super(msg); }
    }

    public static final class Result {
        public final boolean isFloat;
        public final Map<String, String> symbolType = new LinkedHashMap<>();  //sysName -> numeric | procedure
        public final Map<Integer, String> nodeType = new HashMap<>();         //tree node id -> ok | numeric | boolean | procedure
        Result(boolean isFloat) { this.isFloat = isFloat; }
        /** the C type used for every declaration of the generated program. */
        public String typ() { return isFloat ? "float" : "int"; }
    }

    private final SemanticAnalyzer.SymbolTable st;
    private final Result res;
    private final Map<String, Integer> arity = new HashMap<>();   //function sysName -> number of parameters

    private TypeAnalyzer(SemanticAnalyzer.SymbolTable st, Result res) {
        this.st = st;
        this.res = res;
    }

    public static Result analyze(Parser.Node root, SemanticAnalyzer.SymbolTable st) {
        List<Parser.Node> all = preorder(root);

        //W1, W2 and the int/float decision
        Parser.Node anyMod = null, anyDiv = null, firstDotNum = null;
        for (Parser.Node n : all) {
            if (!n.isLeaf) continue;
            if (n.contents.equals("mod") && anyMod == null) anyMod = n;
            if (n.contents.equals("div") && anyDiv == null) anyDiv = n;
            if (isNum(n) && n.contents.indexOf('.') >= 0 && firstDotNum == null) firstDotNum = n;
        }
        if (anyMod != null && anyDiv != null) {
            throw new TypeException("A float-integer-conflict might perhaps be possible");
        }
        if (anyMod != null && firstDotNum != null) {
            throw new TypeException("Type error at " + at(firstDotNum) + ": the number '" + firstDotNum.contents
                + "' has a decimal point, but the program uses 'mod' (line " + anyMod.line + ", column " + anyMod.col
                + ") which works on integers only.");
        }
        boolean isFloat = anyDiv != null || firstDotNum != null;
        if (!isFloat) {
            BigInteger lo = BigInteger.valueOf(Integer.MIN_VALUE), hi = BigInteger.valueOf(Integer.MAX_VALUE);
            for (Parser.Node n : all) {
                if (n.isLeaf && isNum(n)) {
                    BigInteger v = new BigInteger(n.contents);
                    if (v.compareTo(lo) < 0 || v.compareTo(hi) > 0) {
                        throw new TypeException("Type error at " + at(n) + ": the number '" + n.contents
                            + "' does not fit into the integer type of the generated program (the program has no"
                            + " 'div' and no decimal numbers, so all numbers are integers between "
                            + Integer.MIN_VALUE + " and " + Integer.MAX_VALUE + ").");
                    }
                }
            }
        }

        Result res = new Result(isFloat);
        TypeAnalyzer ta = new TypeAnalyzer(st, res);
        ta.declareTypes(all);
        Parser.Node p = child(root, "P");
        String t = ta.checkP(p);
        res.nodeType.put(root.id, t);   //SPL_PROG -> P $ : ok if P is ok
        return res;
    }

    //declarations: every variable is numeric, a void function is a procedure, a num function is numeric
    private void declareTypes(List<Parser.Node> all) {
        for (Parser.Node n : all) {
            if (n.isLeaf) continue;
            if (n.contents.equals("V_DECL")) {
                for (Parser.Node c : n.children)
                    if (isName(c)) setSym(c, "numeric");
            } else if (n.contents.equals("F_TYPE")) {
                boolean isVoid = n.children.get(0).contents.equals("void");
                Parser.Node nameLeaf = n.children.get(1);
                setSym(nameLeaf, isVoid ? "procedure" : "numeric");
                arity.put(st.byNode.get(nameLeaf.id).sysName, countNames(child(n, "V_DECL")));
            }
        }
    }

    private void setSym(Parser.Node nameLeaf, String type) {
        SemanticAnalyzer.Symbol s = st.byNode.get(nameLeaf.id);
        if (s == null) throw new IllegalStateException("name leaf " + nameLeaf.id + " has no symbol (run phase 2a first)");
        res.symbolType.put(s.sysName, type);
    }

    private String symType(Parser.Node nameLeaf) {
        SemanticAnalyzer.Symbol s = st.byNode.get(nameLeaf.id);
        if (s == null) return "unknown";
        return res.symbolType.getOrDefault(s.sysName, "unknown");
    }

    private String done(Parser.Node n, String type) {
        res.nodeType.put(n.id, type);
        return type;
    }

    //P -> V_DECL : F_DECL : ALGO
    private String checkP(Parser.Node p) {
        String v = checkVDecl(child(p, "V_DECL"));
        String f = checkFDecl(child(p, "F_DECL"));
        String a = checkAlgo(child(p, "ALGO"));
        return done(p, v.equals("ok") && f.equals("ok") && a.equals("ok") ? "ok" : "unknown");
    }

    //V_DECL -> eps | NAME V_DECL   (the names were typed numeric in declareTypes)
    private String checkVDecl(Parser.Node v) {
        for (Parser.Node x = v; x != null; x = childOrNull(x, "V_DECL")) {
            for (Parser.Node c : x.children)
                if (isName(c) && !symType(c).equals("numeric"))
                    throw err(c, "the variable '" + c.contents + "' is not numeric");
            done(x, "ok");
        }
        return "ok";
    }

    //F_DECL -> eps | F_TYPE F_DECL   (iterative: a long list of functions must not overflow the stack)
    private String checkFDecl(Parser.Node f) {
        for (Parser.Node x = f; x != null; x = childOrNull(x, "F_DECL")) {
            Parser.Node ft = childOrNull(x, "F_TYPE");
            if (ft != null) checkFType(ft);
            done(x, "ok");
        }
        return "ok";
    }

    //F_TYPE -> void NAME ( V_DECL ) { P return }   |   num NAME ( V_DECL ) { P return ( TERM ) }
    private String checkFType(Parser.Node ft) {
        boolean isVoid = ft.children.get(0).contents.equals("void");
        Parser.Node nameLeaf = ft.children.get(1);
        String v = checkVDecl(child(ft, "V_DECL"));
        String p = checkP(child(ft, "P"));
        if (!v.equals("ok") || !p.equals("ok")) throw err(nameLeaf, "the body of function '" + nameLeaf.contents + "' is not well typed");
        if (!isVoid) {
            Parser.Node term = child(ft, "TERM");
            String t = checkTerm(term);
            if (!t.equals("numeric"))
                throw err(firstLeaf(term), "the return value of function '" + nameLeaf.contents + "' is not numeric");
        }
        return done(ft, "ok");
    }

    //ALGO -> eps | INSTR ; ALGO   (iterative over the list)
    private String checkAlgo(Parser.Node a) {
        for (Parser.Node x = a; x != null; x = childOrNull(x, "ALGO")) {
            Parser.Node instr = childOrNull(x, "INSTR");
            if (instr != null) {
                String t = checkInstr(instr);
                if (!t.equals("ok")) throw err(firstLeaf(instr), "this instruction is not well typed");
            }
            done(x, "ok");
        }
        return "ok";
    }

    private String checkInstr(Parser.Node instr) {
        Parser.Node first = instr.children.get(0);
        if (first.isLeaf) {
            switch (first.contents) {
                case "print": return done(instr, checkOutp(instr.children.get(1)).equals("ok") ? "ok" : "unknown");
                case "nop":
                case "comment": return done(instr, "ok");
                default: throw new IllegalStateException("unexpected INSTR leaf " + first.contents);
            }
        }
        switch (first.contents) {
            case "ASSIGN": return done(instr, checkAssign(first));
            case "BRANCH": return done(instr, checkBranch(first));
            case "LOOP":   return done(instr, checkLoop(first));
            case "CALL": {
                String t = checkCall(first);
                Parser.Node nameLeaf = first.children.get(0);
                if (!t.equals("procedure"))
                    throw err(nameLeaf, "the function '" + nameLeaf.contents + "' returns a number, but a call used as an"
                        + " instruction must be a void procedure (use its value in a term, e.g. an assignment, instead)");
                return done(instr, "ok");
            }
            default: throw new IllegalStateException("unexpected INSTR child " + first.contents);
        }
    }

    //OUTP -> ( TERM ) | STRING
    private String checkOutp(Parser.Node o) {
        if (o.children.get(0).isLeaf && o.children.size() == 1) return done(o, "ok");   //STRING
        Parser.Node term = child(o, "TERM");
        if (!checkTerm(term).equals("numeric")) throw err(firstLeaf(term), "only numeric terms can be printed");
        return done(o, "ok");
    }

    //ASSIGN -> NAME = TERM
    private String checkAssign(Parser.Node a) {
        Parser.Node nameLeaf = a.children.get(0);
        String tt = checkTerm(child(a, "TERM"));
        if (!tt.equals("numeric")) throw err(firstLeaf(child(a, "TERM")), "the right-hand side is not numeric");
        if (!symType(nameLeaf).equals("numeric"))
            throw err(nameLeaf, "'" + nameLeaf.contents + "' is not a numeric variable and cannot be assigned to");
        return done(a, "ok");
    }

    //CALL -> NAME ( INPUT )   type_of(CALL) = type_of(NAME)
    private String checkCall(Parser.Node call) {
        Parser.Node nameLeaf = call.children.get(0);
        String nt = symType(nameLeaf);
        if (nt.equals("unknown")) throw err(nameLeaf, "'" + nameLeaf.contents + "' has no known type");
        if (!checkInput(child(call, "INPUT")).equals("ok")) throw err(nameLeaf, "an argument of this call is not numeric");
        SemanticAnalyzer.Symbol s = st.byNode.get(nameLeaf.id);
        Integer expected = arity.get(s.sysName);
        int got = countTerms(child(call, "INPUT"));
        if (expected != null && expected != got)
            throw err(nameLeaf, "the function '" + nameLeaf.contents + "' has " + expected + " parameter(s) but is called with "
                + got + " argument(s)");
        return done(call, nt);
    }

    //INPUT -> eps | TERM INPUT
    private String checkInput(Parser.Node in) {
        for (Parser.Node x = in; x != null; x = childOrNull(x, "INPUT")) {
            Parser.Node term = childOrNull(x, "TERM");
            if (term != null && !checkTerm(term).equals("numeric")) throw err(firstLeaf(term), "this argument is not numeric");
            done(x, "ok");
        }
        return "ok";
    }

    //TERM -> NAME | NUM | CALL | mod/div/add/sub/mul ( TERM TERM ) | neg ( TERM )
    private String checkTerm(Parser.Node t) {
        Parser.Node first = t.children.get(0);
        if (!first.isLeaf) {   //CALL
            String ct = checkCall(first);
            if (!ct.equals("numeric")) {
                Parser.Node nameLeaf = first.children.get(0);
                throw err(nameLeaf, "the procedure '" + nameLeaf.contents + "' returns no value, so its call cannot be used inside a term");
            }
            return done(t, "numeric");
        }
        if (isName(first)) {
            if (!symType(first).equals("numeric")) throw err(first, "'" + first.contents + "' is not a numeric variable");
            return done(t, "numeric");
        }
        if (isNum(first)) return done(t, "numeric");
        switch (first.contents) {
            case "mod": case "div": case "add": case "sub": case "mul": {
                requireNumeric(t.children.get(2));
                requireNumeric(t.children.get(3));
                return done(t, "numeric");
            }
            case "neg":
                requireNumeric(t.children.get(2));
                return done(t, "numeric");
            default: throw new IllegalStateException("unexpected TERM leaf " + first.contents);
        }
    }

    private void requireNumeric(Parser.Node term) {
        if (!checkTerm(term).equals("numeric")) throw err(firstLeaf(term), "this operand is not numeric");
    }

    //BRANCH -> if BOOL then { ALGO } else { ALGO }
    private String checkBranch(Parser.Node b) {
        requireBoolean(child(b, "BOOL"));
        List<Parser.Node> algos = children(b, "ALGO");
        checkAlgo(algos.get(0));
        checkAlgo(algos.get(1));
        return done(b, "ok");
    }

    //LOOP -> COND BOOL do { ALGO } | do { ALGO } COND BOOL
    private String checkLoop(Parser.Node l) {
        done(child(l, "COND"), "ok");
        requireBoolean(child(l, "BOOL"));
        checkAlgo(child(l, "ALGO"));
        return done(l, "ok");
    }

    private void requireBoolean(Parser.Node b) {
        if (!checkBool(b).equals("boolean")) throw err(firstLeaf(b), "this is not a boolean expression");
    }

    //BOOL -> not ( BOOL ) | and/or ( BOOL BOOL ) | eq/larger/lesser ( TERM TERM )
    private String checkBool(Parser.Node b) {
        String op = b.children.get(0).contents;
        switch (op) {
            case "not":
                requireBoolean(b.children.get(2));
                return done(b, "boolean");
            case "and": case "or":
                requireBoolean(b.children.get(2));
                requireBoolean(b.children.get(3));
                return done(b, "boolean");
            case "eq": case "larger": case "lesser":
                requireNumeric(b.children.get(2));
                requireNumeric(b.children.get(3));
                return done(b, "boolean");
            default: throw new IllegalStateException("unexpected BOOL operator " + op);
        }
    }

    //helpers
    private static boolean isName(Parser.Node n) { return n.isLeaf && n.contents.startsWith("#"); }

    //a NUM token: the only leaves that start with a digit or a minus sign (names start with #, strings with ")
    private static boolean isNum(Parser.Node n) {
        if (!n.isLeaf || n.contents.isEmpty()) return false;
        char c = n.contents.charAt(0);
        return Character.isDigit(c) || c == '-';
    }

    private static Parser.Node childOrNull(Parser.Node n, String contents) {
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) return c;
        return null;
    }
    private static Parser.Node child(Parser.Node n, String contents) {
        Parser.Node c = childOrNull(n, contents);
        if (c == null) throw new IllegalStateException("malformed tree: <" + n.contents + "> has no <" + contents + ">");
        return c;
    }
    private static List<Parser.Node> children(Parser.Node n, String contents) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) out.add(c);
        return out;
    }
    private static int countNames(Parser.Node vdecl) {
        int k = 0;
        for (Parser.Node x = vdecl; x != null; x = childOrNull(x, "V_DECL"))
            for (Parser.Node c : x.children) if (isName(c)) k++;
        return k;
    }
    private static int countTerms(Parser.Node input) {
        int k = 0;
        for (Parser.Node x = input; x != null; x = childOrNull(x, "INPUT"))
            if (childOrNull(x, "TERM") != null) k++;
        return k;
    }
    //leftmost token of a subtree, for error positions
    private static Parser.Node firstLeaf(Parser.Node n) {
        Parser.Node x = n;
        while (!x.isLeaf) {
            if (x.children.isEmpty()) return x;
            x = x.children.get(0);
        }
        return x;
    }
    private static String at(Parser.Node leaf) { return "line " + leaf.line + ", column " + leaf.col; }
    private static TypeException err(Parser.Node where, String msg) {
        Parser.Node leaf = firstLeaf(where);
        return new TypeException("Type error at " + at(leaf) + ": " + msg + ".");
    }
    private static List<Parser.Node> preorder(Parser.Node root) {
        List<Parser.Node> out = new ArrayList<>();
        Deque<Parser.Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Parser.Node n = stack.pop();
            out.add(n);
            for (int i = n.children.size() - 1; i >= 0; i--) stack.push(n.children.get(i));
        }
        return out;
    }

    /** appends the type information to a symbol table dump (spec: "fill the symbol table with type information"). */
    public static String describe(Result r, SemanticAnalyzer.SymbolTable st) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nTYPES (phase 2b)\n");
        sb.append("program type: ").append(r.isFloat ? "float (real numbers)" : "int (whole numbers)").append("\n");
        sb.append(String.format("%-8s %-12s %s%n", "sysName", "original", "type"));
        for (SemanticAnalyzer.Symbol s : st.symbols)
            sb.append(String.format("%-8s %-12s %s%n", s.sysName, s.origName, r.symbolType.getOrDefault(s.sysName, "unknown")));
        return sb.toString();
    }
}
