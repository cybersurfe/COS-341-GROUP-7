import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
Phases 3a + 3b: C++ code generation (ALGO translation + inlining of functions).
input : syntax tree + symbol table (2a, every name leaf has a sys name) + type result (2b, int or float)
output: the text of one C++ file.

layout of the output file (3b sheet):
    (open C comment) code mine: the code of all functions, as plain text inside ONE C comment, so it cannot execute (close C comment)
    #include <iostream>
    int main() { variables  ALGO  return 0; }

how functions work ("inlining", no run-time stack):
  * functions are generated bottom-up: the nested functions of f are generated first, so when the
    body of f contains a call to its own sub-function g, g is inlined INTO f's body already inside the mine.
  * a call is replaced by { copy of the callee body } at the call site. one copy per call.
  * every copy gets fresh labels (all labels of the copy, including labels coming from copies nested in it,
    are renamed consistently), otherwise a function with a loop called twice would define a label twice.
  * every copy lives in its own C block, so its parameters / locals / temporaries never clash with another copy.
  * arguments are first evaluated into fresh temporaries p1..pN in the CALLER's context, then copied into the
    callee's parameters. (the sheet writes "p1 = a1", but the semantically right direction is  a1 = p1.)

boolean translation (3a sheet) is "jumping code": gen(BOOL, T, F) emits code that goes to T if the boolean is
true and to F if it is false. contract: the caller places the label F right behind the emitted code (or follows
it with "goto F"), so falling off the end of the code means "false".
  eq/larger/lesser :  if (x op y) goto T;
  not B            :  gen(B, F, T)  goto T;                      (swap the labels, as the sheet says, plus the jump
                                                                that makes the "false" case of B reach T)
  or(B1,B2)        :  gen(B1, T, Lm)  Lm:  gen(B2, T, F)
  and(B1,B2)       :  gen(B1, Lm, F)  goto F;  Lm:  gen(B2, T, F)
the sheet's literal "swap only" rules for and/not would make 'and' behave like 'or' (and 'not' always reach the
false branch), which is why the extra gotos are there. verified by differential testing against an interpreter.

all declarations are plain "typ x;" without initializer on purpose: C++ forbids a goto that jumps over an
initialisation, but jumping over a declaration of an uninitialised int/float is legal.
*/
public final class CodeGenerator {

    public static final class CodeGenException extends RuntimeException {
        public CodeGenException(String msg) { super(msg); }
    }

    //one generated function: everything needed to inline it at a call site
    private static final class Fn {
        String sys, orig;
        boolean isNum;
        final List<String> params = new ArrayList<>();
        String bodyCode = "";   //local variable declarations + translated ALGO (nested calls already inlined)
        String retCode = "";    //num functions: declaration of r + code that computes the return term into r
        String retVar = null;   //r, the "return value" variable
        String mine = "";       //human readable version for the code mine
    }

    //inlining copies a body once per call, so nested calls multiply. beyond this the output is useless and memory runs out.
    private static final int MAX_INLINED_CHARS = 16_000_000;

    private static final Pattern LABEL = Pattern.compile("\\bL\\d+\\b");
    private static final Pattern INLINE_COMMENT = Pattern.compile("/\\*(.*?)\\*/");

    private final SemanticAnalyzer.SymbolTable st;
    private final String typ;
    private int nVar = 0, nLabel = 0;
    private final Map<String, Fn> fns = new HashMap<>();

    private CodeGenerator(SemanticAnalyzer.SymbolTable st, String typ) {
        this.st = st;
        this.typ = typ;
    }

    public static String generate(Parser.Node root, SemanticAnalyzer.SymbolTable st, TypeAnalyzer.Result types) {
        CodeGenerator g = new CodeGenerator(st, types.typ());
        Parser.Node p = child(root, "P");   //SPL_PROG -> P $

        StringBuilder mine = new StringBuilder();
        g.genFDecl(child(p, "F_DECL"), mine);
        String vars = g.declareVars(child(p, "V_DECL"));
        String algo = g.genAlgo(child(p, "ALGO"));

        StringBuilder out = new StringBuilder();
        out.append("/* begin of the non-executable code-mine for later inlining\n");
        out.append(sanitizeForComment(mine.toString()));
        out.append("end of the non-executable code-mine */\n");
        out.append("/* begin of executable C code */\n");
        out.append("#include <iostream>\n");
        out.append("int main()\n{\n");
        out.append(vars);
        out.append(algo);
        out.append("return 0;\n");
        out.append("} /* end of executable C */\n");
        return indent(out.toString());
    }

    //functions

    //F_DECL -> eps | F_TYPE F_DECL
    private void genFDecl(Parser.Node fdecl, StringBuilder mineOut) {
        for (Parser.Node x = fdecl; x != null; x = childOrNull(x, "F_DECL")) {
            Parser.Node ft = childOrNull(x, "F_TYPE");
            if (ft != null) mineOut.append(genFType(ft).mine);
        }
    }

    //F_TYPE -> void NAME ( V_DECL ) { P return }  |  num NAME ( V_DECL ) { P return ( TERM ) }
    private Fn genFType(Parser.Node ft) {
        Fn f = new Fn();
        f.isNum = ft.children.get(0).contents.equals("num");
        Parser.Node nameLeaf = ft.children.get(1);
        SemanticAnalyzer.Symbol fs = st.byNode.get(nameLeaf.id);
        f.sys = fs.sysName;
        f.orig = fs.origName;
        for (Parser.Node n : nameLeaves(child(ft, "V_DECL"))) f.params.add(sys(n));

        Parser.Node body = child(ft, "P");
        StringBuilder nestedMine = new StringBuilder();
        genFDecl(child(body, "F_DECL"), nestedMine);   //sub-functions first, so calls to them can be inlined below

        f.bodyCode = declareVars(child(body, "V_DECL")) + genAlgo(child(body, "ALGO"));
        if (f.isNum) {
            f.retVar = f.sys + "r" + (++nVar);
            f.retCode = typ + " " + f.retVar + ";\n" + genTerm(child(ft, "TERM"), f.retVar);
        }

        StringBuilder m = new StringBuilder();
        String what = f.isNum ? "Function " : "void-procedure ";
        m.append("begin of code-mine for ").append(what).append(f.sys).append(" (").append(f.orig).append(")\n");
        m.append("Parameters for inlining:\n");
        for (String a : f.params) m.append(typ).append(" ").append(a).append(";\n");
        m.append("Body of the procedure:\n");
        m.append(nestedMine);
        m.append(f.bodyCode);
        m.append(f.retCode);
        m.append("end of code-mine for ").append(what).append(f.sys).append("\n");
        if (f.isNum) m.append("The result is found in the return value ").append(f.retVar).append("\n");
        f.mine = m.toString();

        fns.put(f.sys, f);
        return f;
    }

    //V_DECL -> eps | NAME V_DECL.  zero-initialised so that a read before the first write is deterministic.
    private String declareVars(Parser.Node vdecl) {
        StringBuilder sb = new StringBuilder();
        for (Parser.Node n : nameLeaves(vdecl)) {
            String s = sys(n);
            sb.append(typ).append(" ").append(s).append(";\n");
            sb.append(s).append(" = 0;\n");
        }
        return sb.toString();
    }

    //CALL = inlining. contVar is the caller's continuation variable (null for a void procedure call).
    private String genCall(Parser.Node call, String contVar) {
        Parser.Node nameLeaf = call.children.get(0);
        Fn f = fns.get(st.byNode.get(nameLeaf.id).sysName);
        if (f == null) throw new CodeGenException("no code-mine entry for function " + nameLeaf.contents
            + " (is it called from a scope that does not declare it?)");

        List<Parser.Node> args = new ArrayList<>();
        for (Parser.Node x = child(call, "INPUT"); x != null; x = childOrNull(x, "INPUT")) {
            Parser.Node t = childOrNull(x, "TERM");
            if (t != null) args.add(t);
        }
        if (args.size() != f.params.size())
            throw new CodeGenException("call of " + nameLeaf.contents + " has " + args.size() + " argument(s), expected " + f.params.size());

        StringBuilder sb = new StringBuilder();
        sb.append("/* call of ").append(f.sys).append(" */\n");
        List<String> ps = new ArrayList<>();
        for (Parser.Node a : args) {   //arguments are evaluated left to right, in the caller's context
            String p = newVar();
            ps.add(p);
            sb.append(typ).append(" ").append(p).append(";\n");
            sb.append(genTerm(a, p));
        }
        StringBuilder copy = new StringBuilder();
        copy.append("{\n");
        for (int i = 0; i < ps.size(); i++) {
            copy.append(typ).append(" ").append(f.params.get(i)).append(";\n");
            copy.append(f.params.get(i)).append(" = ").append(ps.get(i)).append(";\n");
        }
        copy.append(f.bodyCode);
        copy.append(f.retCode);
        if (contVar != null) {
            if (!f.isNum) throw new CodeGenException("procedure " + f.orig + " used as a value");
            copy.append(contVar).append(" = ").append(f.retVar).append(";\n");
        }
        copy.append("}\n");
        if (copy.length() > MAX_INLINED_CHARS)
            throw new CodeGenException("the program is too large after inlining: the calls to '" + f.orig
                + "' expand to more than " + MAX_INLINED_CHARS / 1_000_000 + " million characters of code (nested functions that call"
                + " their sub-functions many times multiply out). Please reduce the number of nested calls");
        sb.append(renameLabels(copy.toString()));   //fresh labels for this copy
        return sb.toString();
    }

    //every label of the copy gets a new unique name, consistently for the definition and all jumps to it
    private String renameLabels(String code) {
        Map<String, String> map = new HashMap<>();
        Matcher m = LABEL.matcher(code);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fresh = map.computeIfAbsent(m.group(), k -> newLabel());
            m.appendReplacement(sb, fresh);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    //ALGO

    //ALGO -> eps | INSTR ; ALGO   (a loop over the list, a long program must not overflow the stack)
    private String genAlgo(Parser.Node algo) {
        StringBuilder sb = new StringBuilder();
        for (Parser.Node x = algo; x != null; x = childOrNull(x, "ALGO")) {
            Parser.Node instr = childOrNull(x, "INSTR");
            if (instr != null) sb.append(genInstr(instr));
            else sb.append("/* end */\n");
        }
        return sb.toString();
    }

    private String genInstr(Parser.Node instr) {
        Parser.Node first = instr.children.get(0);
        if (first.isLeaf) {
            switch (first.contents) {
                case "print": return genPrint(instr.children.get(1));
                case "nop": return "/* nop */ ;\n";
                case "comment": return "/* " + instr.children.get(1).contents + " */ ;\n";
                default: throw new CodeGenException("unexpected instruction " + first.contents);
            }
        }
        switch (first.contents) {
            case "ASSIGN": return genAssign(first);
            case "BRANCH": return genBranch(first);
            case "LOOP": return genLoop(first);
            case "CALL": return genCall(first, null);
            default: throw new CodeGenException("unexpected instruction node " + first.contents);
        }
    }

    //OUTP -> STRING | ( TERM )
    private String genPrint(Parser.Node outp) {
        if (outp.children.size() == 1) return "std::cout << " + outp.children.get(0).contents + ";\n";
        String v = newVar();
        return typ + " " + v + ";\n" + genTerm(child(outp, "TERM"), v) + "std::cout << " + v + ";\n";
    }

    //ASSIGN -> NAME = TERM
    private String genAssign(Parser.Node a) {
        String v = newVar();
        return typ + " " + v + ";\n" + genTerm(child(a, "TERM"), v) + sys(a.children.get(0)) + " = " + v + ";\n";
    }

    //BRANCH -> if BOOL then { ALGO } else { ALGO }; no else in the target, see the layout below
    private String genBranch(Parser.Node b) {
        List<Parser.Node> algos = children(b, "ALGO");
        String la = newLabel(), lb = newLabel(), lc = newLabel();
        StringBuilder sb = new StringBuilder();
        sb.append("/* if */\n");
        sb.append(genBool(child(b, "BOOL"), la, lb));
        sb.append(lb).append(": ; /* else */\n");
        sb.append(genAlgo(algos.get(1)));
        sb.append("goto ").append(lc).append(";\n");
        sb.append(la).append(": ; /* then */\n");
        sb.append(genAlgo(algos.get(0)));
        sb.append(lc).append(": ; /* continue */\n");
        return sb.toString();
    }

    //LOOP -> COND BOOL do { ALGO } | do { ALGO } COND BOOL
    private String genLoop(Parser.Node l) {
        boolean isUntil = child(l, "COND").children.get(0).contents.equals("until");
        Parser.Node bool = child(l, "BOOL");
        Parser.Node body = child(l, "ALGO");
        boolean testFirst = l.children.get(0).contents.equals("COND") && !l.children.get(0).isLeaf;
        StringBuilder sb = new StringBuilder();
        if (testFirst) {
            //until B do {A}:  ls: if B goto le;  d: A  goto ls;  le:      (while B == until not B)
            String ls = newLabel(), le = newLabel(), d = newLabel();
            sb.append(ls).append(": ; /* ").append(isUntil ? "until" : "while").append(" */\n");
            sb.append(isUntil ? genBool(bool, le, d) : genNot(bool, le, d));
            sb.append(d).append(": ; /* do */\n");
            sb.append(genAlgo(body));
            sb.append("goto ").append(ls).append(";\n");
            sb.append(le).append(": ; /* exit */\n");
        } else {
            //do {A} while B:  ld: A  if B goto ld;  le:                  (do-until B == do-while not B)
            String ld = newLabel(), le = newLabel();
            sb.append(ld).append(": ; /* do */\n");
            sb.append(genAlgo(body));
            sb.append("/* ").append(isUntil ? "until" : "while").append(" */\n");
            sb.append(isUntil ? genNot(bool, ld, le) : genBool(bool, ld, le));
            sb.append(le).append(": ; /* exit */\n");
        }
        return sb.toString();
    }

    //BOOL: jumping code, see the class comment

    //gen(not B, T, F) = gen(B, F, T) goto T;
    private String genNot(Parser.Node b, String t, String f) {
        return genBool(b, f, t) + "goto " + t + ";\n";
    }

    private String genBool(Parser.Node b, String t, String f) {
        String op = b.children.get(0).contents;
        switch (op) {
            case "eq": case "larger": case "lesser": {
                String x = newVar(), y = newVar();
                String c = op.equals("eq") ? "==" : op.equals("larger") ? ">" : "<";
                return typ + " " + x + ";\n" + typ + " " + y + ";\n"
                    + genTerm(b.children.get(2), x) + genTerm(b.children.get(3), y)
                    + "if (" + x + " " + c + " " + y + ") goto " + t + ";\n";
            }
            case "not":
                return genNot(b.children.get(2), t, f);
            case "or": {
                String lm = newLabel();
                return genBool(b.children.get(2), t, lm) + lm + ": ;\n" + genBool(b.children.get(3), t, f);
            }
            case "and": {
                String lm = newLabel();
                return genBool(b.children.get(2), lm, f) + "goto " + f + ";\n" + lm + ": ;\n" + genBool(b.children.get(3), t, f);
            }
            default: throw new CodeGenException("unexpected boolean operator " + op);
        }
    }

    //TERM: trans(TERM, resultVar)

    private String genTerm(Parser.Node t, String r) {
        Parser.Node first = t.children.get(0);
        if (!first.isLeaf) return genCall(first, r);               //TERM -> CALL
        String c = first.contents;
        if (c.startsWith("#")) return r + " = " + sys(first) + ";\n";   //TERM -> NAME
        switch (c) {
            case "add": case "sub": case "mul": case "div": case "mod": {
                String x = newVar(), y = newVar();
                String sym = c.equals("add") ? "+" : c.equals("sub") ? "-" : c.equals("mul") ? "*" : c.equals("div") ? "/" : "%";
                return typ + " " + x + ";\n" + typ + " " + y + ";\n"
                    + genTerm(t.children.get(2), x) + genTerm(t.children.get(3), y)
                    + r + " = " + x + " " + sym + " " + y + ";\n";
            }
            case "neg": {
                String x = newVar();
                return typ + " " + x + ";\n" + genTerm(t.children.get(2), x) + r + " = -" + x + ";\n";
            }
            default:   //TERM -> NUM: a number stays the same in the target code
                return r + " = " + c + ";\n";
        }
    }

    //helpers

    private String newVar() { return "v" + (++nVar); }
    private String newLabel() { return "L" + (++nLabel); }

    private String sys(Parser.Node nameLeaf) {
        SemanticAnalyzer.Symbol s = st.byNode.get(nameLeaf.id);
        if (s == null) throw new CodeGenException("name '" + nameLeaf.contents + "' has no symbol-table entry");
        return s.sysName;
    }

    private static List<Parser.Node> nameLeaves(Parser.Node vdecl) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node x = vdecl; x != null; x = childOrNull(x, "V_DECL"))
            for (Parser.Node c : x.children) if (c.isLeaf && c.contents.startsWith("#")) out.add(c);
        return out;
    }
    private static Parser.Node childOrNull(Parser.Node n, String contents) {
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) return c;
        return null;
    }
    private static Parser.Node child(Parser.Node n, String contents) {
        Parser.Node c = childOrNull(n, contents);
        if (c == null) throw new CodeGenException("malformed tree: <" + n.contents + "> has no <" + contents + ">");
        return c;
    }
    private static List<Parser.Node> children(Parser.Node n, String contents) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(contents)) out.add(c);
        return out;
    }

    /*
    C does not nest comments: a small comment inside the code mine's big comment would end it early.
    so inside the mine every small comment becomes a double-slash comment (harmless inside a block comment).
    */
    private static String sanitizeForComment(String s) {
        String r = INLINE_COMMENT.matcher(s).replaceAll("//$1");
        return r.replace("*/", "* /").replace("/*", "/ *");
    }

    //2-space indentation by brace depth, purely cosmetic (the spec wants a neat, legible file)
    private static String indent(String code) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inMine = true;
        for (String line : code.split("\n", -1)) {
            String t = line.trim();
            if (t.equals("end of the non-executable code-mine */")) inMine = false;
            if (inMine || t.isEmpty()) { sb.append(line).append("\n"); continue; }
            if (t.startsWith("}")) depth--;
            for (int i = 0; i < Math.max(depth, 0); i++) sb.append("  ");
            sb.append(t).append("\n");
            if (t.equals("{")) depth++;
        }
        String r = sb.toString();
        return r.endsWith("\n\n") ? r.substring(0, r.length() - 1) : r;
    }
}
