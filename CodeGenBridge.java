import codegen.CodeGen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodeGenBridge {

    private CodeGenBridge() {}

    public static Map<Integer, String> nameMap(SemanticAnalyzer.SymbolTable st) {
        Map<Integer, String> m = new HashMap<>();
        st.byNode.forEach((id, sym) -> m.put(id, sym.sysName));
        return m;
    }

    public static String generate(Parser.Node root, SemanticAnalyzer.SymbolTable st, TypeAnalyzer.Result ty) {
        CodeGen cg = new CodeGen(CodeGen.typeFor(ty.isFloat));
        cg.setNames(nameMap(st));
        return cg.generate(buildAlgo(root));
    }

    public static String compile(String src) {
        Parser.Node root = new Parser(new Lexer.TokenStream(Lexer.tokenize(src + "\n"))).parseProgram();
        SemanticAnalyzer.SymbolTable st = SemanticAnalyzer.analyze(root);
        TypeAnalyzer.Result ty = TypeAnalyzer.analyze(root, st);
        return generate(root, st, ty);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("usage: java CodeGenBridge program.txt"); System.exit(2); }
        String src = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        try {
            System.out.print(compile(src));
        } catch (Lexer.LexException | Parser.ParseException | TypeAnalyzer.TypeException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    public static CodeGen.Algo buildAlgo(Parser.Node root) {
        Parser.Node p = root.contents.equals("P") ? root : child(root, "P");
        if (p == null) throw new IllegalStateException("malformed tree: no <P> under <" + root.contents + ">");
        Parser.Node algo = child(p, "ALGO");
        if (algo == null) throw new IllegalStateException("malformed tree: <P> has no <ALGO>");
        return algo(algo);
    }

    private static CodeGen.Algo algo(Parser.Node n) {
        CodeGen.Algo a = new CodeGen.Algo();
        collectInstrs(n, a);
        return a;
    }

    private static void collectInstrs(Parser.Node algo, CodeGen.Algo into) {
        for (Parser.Node c : algo.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("INSTR")) into.instrs.add(instr(c));
            else if (c.contents.equals("ALGO")) collectInstrs(c, into);
        }
    }

    private static CodeGen.Instr instr(Parser.Node n) {
        for (Parser.Node c : n.children) {
            if (!c.isLeaf) continue;
            switch (c.contents) {
                case "nop":     return new CodeGen.Nop();
                case "comment": return new CodeGen.Comment(stringOf(n));
                case "print":   return printInstr(n);
                default: break;
            }
        }
        for (Parser.Node c : n.children) {
            if (c.isLeaf) continue;
            switch (c.contents) {
                case "ASSIGN": return assign(c);
                case "BRANCH": return branch(c);
                case "LOOP":   return loop(c);
                case "CALL":
                    throw new UnsupportedOperationException(
                        "function calls are translated in part 3b (line " + line(c) + ")");
                case "OUTP":   return printInstr(n);
                default: break;
            }
        }
        throw new IllegalStateException("cannot translate <INSTR> id=" + n.id);
    }

    private static CodeGen.Instr printInstr(Parser.Node instr) {
        Parser.Node term = null;
        for (Parser.Node c : instr.children) {
            if (c.isLeaf) continue;
            if (c.contents.equals("TERM")) term = c;
            else if (c.contents.equals("OUTP")) {
                Parser.Node t = child(c, "TERM");
                if (t != null) term = t;
            }
        }
        if (term != null) return new CodeGen.PrintTerm(term(term));
        return new CodeGen.PrintString(stringOf(instr));
    }

    private static String stringOf(Parser.Node instr) {
        for (Parser.Node c : instr.children) {
            if (c.isLeaf && c.contents.startsWith("\"")) return c.contents;
            if (!c.isLeaf && c.contents.equals("OUTP"))
                for (Parser.Node x : c.children)
                    if (x.isLeaf && x.contents.startsWith("\"")) return x.contents;
        }
        throw new IllegalStateException("no STRING under <INSTR> id=" + instr.id);
    }

    private static CodeGen.Instr assign(Parser.Node n) {
        Parser.Node nameLeaf = null, term = null;
        for (Parser.Node c : n.children) {
            if (c.isLeaf && isName(c.contents) && nameLeaf == null) nameLeaf = c;
            if (!c.isLeaf && c.contents.equals("TERM")) term = c;
        }
        if (nameLeaf == null || term == null) throw new IllegalStateException("malformed <ASSIGN> id=" + n.id);
        return new CodeGen.Assign(nameLeaf.id, term(term));
    }

    private static CodeGen.Instr branch(Parser.Node n) {
        Parser.Node cond = child(n, "BOOL");
        List<Parser.Node> algos = children(n, "ALGO");
        if (cond == null || algos.size() != 2) throw new IllegalStateException("malformed <BRANCH> id=" + n.id);
        return new CodeGen.Branch(bool(cond), algo(algos.get(0)), algo(algos.get(1)));
    }

    private static CodeGen.Instr loop(Parser.Node n) {
        Parser.Node cond = child(n, "BOOL"), body = child(n, "ALGO");
        if (cond == null || body == null) throw new IllegalStateException("malformed <LOOP> id=" + n.id);
        int algoIdx = -1, condIdx = -1;
        String word = null;
        for (int i = 0; i < n.children.size(); i++) {
            Parser.Node c = n.children.get(i);
            if (!c.isLeaf && c.contents.equals("ALGO")) algoIdx = i;
            if (!c.isLeaf && c.contents.equals("COND")) {
                condIdx = i;
                Parser.Node l = firstLeaf(c);
                if (l != null) word = l.contents;
            } else if (c.isLeaf && (c.contents.equals("while") || c.contents.equals("until"))) {
                condIdx = i;
                word = c.contents;
            }
        }
        if (word == null) throw new IllegalStateException("<LOOP> id=" + n.id + " has no while/until");
        boolean doFirst = algoIdx < condIdx;
        boolean isWhile = word.equals("while");
        CodeGen.LoopKind k = doFirst
            ? (isWhile ? CodeGen.LoopKind.DO_WHILE : CodeGen.LoopKind.DO_UNTIL)
            : (isWhile ? CodeGen.LoopKind.WHILE_DO : CodeGen.LoopKind.UNTIL_DO);
        return new CodeGen.Loop(k, bool(cond), algo(body));
    }

    private static CodeGen.Term term(Parser.Node n) {
        Parser.Node first = n.children.get(0);
        if (!first.isLeaf) {
            if (first.contents.equals("CALL"))
                throw new UnsupportedOperationException(
                    "function calls are translated in part 3b (line " + line(first) + ")");
            throw new IllegalStateException("unexpected <" + first.contents + "> in <TERM> id=" + n.id);
        }
        String s = first.contents;
        List<Parser.Node> sub = children(n, "TERM");
        switch (s) {
            case "mod": case "div": case "add": case "sub": case "mul":
                if (sub.size() != 2) throw new IllegalStateException("'" + s + "' needs 2 operands, id=" + n.id);
                return new CodeGen.BinOp(s, term(sub.get(0)), term(sub.get(1)));
            case "neg":
                if (sub.size() != 1) throw new IllegalStateException("'neg' needs 1 operand, id=" + n.id);
                return new CodeGen.Neg(term(sub.get(0)));
            default:
                if (isName(s)) return new CodeGen.Name(first.id);
                return new CodeGen.Num(s);
        }
    }

    private static CodeGen.Bool_ bool(Parser.Node n) {
        String op = n.children.get(0).contents;
        List<Parser.Node> bs = children(n, "BOOL"), ts = children(n, "TERM");
        switch (op) {
            case "not": return new CodeGen.Not(bool(bs.get(0)));
            case "and": return new CodeGen.And(bool(bs.get(0)), bool(bs.get(1)));
            case "or":  return new CodeGen.Or(bool(bs.get(0)), bool(bs.get(1)));
            case "eq": case "larger": case "lesser":
                return new CodeGen.Cmp(op, term(ts.get(0)), term(ts.get(1)));
            default: throw new IllegalStateException("unknown BOOL operator '" + op + "' id=" + n.id);
        }
    }

    private static boolean isName(String s) { return s.startsWith("#"); }

    private static Parser.Node child(Parser.Node n, String kind) {
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(kind)) return c;
        return null;
    }

    private static List<Parser.Node> children(Parser.Node n, String kind) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node c : n.children) if (!c.isLeaf && c.contents.equals(kind)) out.add(c);
        return out;
    }

    private static Parser.Node firstLeaf(Parser.Node n) {
        if (n.isLeaf) return n;
        for (Parser.Node c : n.children) { Parser.Node l = firstLeaf(c); if (l != null) return l; }
        return null;
    }

    private static String line(Parser.Node n) {
        Parser.Node l = firstLeaf(n);
        return l == null ? "?" : l.line + ", column " + l.col;
    }
}
