import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
Phase 2a: semantic analysis of scopes and names

input : the syntax tree from Parser (SPL_PROG -> P -> V_DECL : F_DECL : ALGO, with every
        function body being a nested P, exactly like Fig.1 in the spec)
output: either a SemanticException (first rule that is violated, with line/column), or a
        SymbolTable in which every user-defined-name leaf of the tree has a unique
        system-generated name (sys1, sys2, ...). Two leaves get the same sys name if and only if
        they are the same computational entity.

the rules (all from the spec):

 VARIABLES
  V1. a variable used in some ALGO at scope level l needs a declaration in a V_DECL at some
      level l' <= l ON THE ANCESTOR CHAIN of that ALGO. the nearest declaration wins.
      (announcement #21: "sideways" declarations in a sibling function do NOT count, which
      falls out for free here because we only ever walk up the parent links of the scopes.)
  V2. all names in the V_DECL of one scope level are different (no double declarations).
  V3. for a function  void/num f( V_DECL ) { P ... }  the parameters and the body's own V_DECL
      share one level: the body may not re-declare (= "mask") a parameter. (parameters must
      also differ from each other, that is just V2 again.)
  note: a local variable of an inner function MAY hide a variable of an outer level, that is
  the "nearer declaration is the relevant one" rule, only parameter-masking is forbidden.

 FUNCTIONS
  F1. a call  f(...)  in the ALGO of a P (or in the return-TERM of a function body, which is
      part of that same P's scope) must find f in the IMMEDIATE F_DECL of that same P.
      no recursion falls out of that: a function lives in the F_DECL of its parent P, but its
      own body is a different (deeper) P, so it can never see its own name, nor its siblings,
      nor anything further up.
  F2. all function names in one F_DECL are different (no overloading).

 RENAMING
  every declaration (parameter, local variable, function) creates one Symbol with a fresh name.
  every use resolves to exactly one Symbol and gets its name. variables and functions are kept
  in two separate namespaces (the spec talks about them separately), but because the fresh
  names come from ONE counter, no two different entities ever share a sys name.
*/
public final class SemanticAnalyzer {

    //errors

    /** thrown for the first violated semantic rule. message contains "line L, column C". */
    public static final class SemanticException extends RuntimeException {
        public SemanticException(String msg) { super(msg); }
    }

    //symbol table

    public enum SymKind { VARIABLE, PARAMETER, FUNCTION }

    /** one declared entity (what the code generator will later look up). */
    public static final class Symbol {
        public final String sysName;       //unique system-generated name, e.g. sys7
        public final String origName;      //the name as the user wrote it, e.g. #x
        public final SymKind kind;
        public final int scopeLevel;       //level of the scope the entity is declared in (a parameter: level of the function body)
        public final int declNodeId;       //tree-node id of the declaring name leaf (foreign key into tree.xml)
        public final String ownerSysName;  //sys name of the function whose body this is declared in, "-" for main
        Symbol(String sysName, String origName, SymKind kind, int scopeLevel, int declNodeId, String ownerSysName) {
            this.sysName = sysName;
            this.origName = origName;
            this.kind = kind;
            this.scopeLevel = scopeLevel;
            this.declNodeId = declNodeId;
            this.ownerSysName = ownerSysName;
        }
    }

    /** all symbols plus the mapping from EVERY user-defined-name leaf (declaration or use) to its symbol. */
    public static final class SymbolTable {
        public final List<Symbol> symbols = new ArrayList<>();
        public final Map<Integer, Symbol> byNode = new LinkedHashMap<>();
    }

    //scopes (only exist during the crawl, the symbol table is what stays)

    private static final class Scope {
        final Scope parent;
        final int level;
        final Symbol owner;                                   //function this scope is the body of, null for main
        final Map<String, Symbol> vars = new HashMap<>();     //params + local variables of THIS level
        final Map<String, Symbol> funcs = new HashMap<>();    //functions of THIS level's F_DECL
        Scope(Scope parent, int level, Symbol owner) {
            this.parent = parent;
            this.level = level;
            this.owner = owner;
        }
    }

    private final SymbolTable table = new SymbolTable();
    private final Map<Integer, Parser.Node> declLeaves = new HashMap<>();   //declaration leaf id -> leaf, for messages
    private int nextSys = 1;

    private SemanticAnalyzer() {}

    /** crawls the whole tree. root is the SPL_PROG node returned by Parser.parseProgram(). */
    public static SymbolTable analyze(Parser.Node root) {
        SemanticAnalyzer a = new SemanticAnalyzer();
        Parser.Node p = inner(root, "P");
        a.analyzeP(p, null, 0, null, null, null);
        return a.table;
    }

    //the crawl

    /*
    handles one P node = one scope level.
      parent      scope of the enclosing P (null for the main program)
      params      name leaves between the round brackets of the function this P is the body of (null for main)
      owner       the function symbol (null for main)
      returnTerm  the TERM after "return (" of a num function (null otherwise). it is evaluated
                  inside this scope, so it is checked like part of the ALGO.
    order: declare variables, declare functions (so every function of this F_DECL is known before
    anything is resolved), recurse into the function bodies, then check the ALGO, then the return term.
    */
    private void analyzeP(Parser.Node p, Scope parent, int level,
                          List<Parser.Node> params, Symbol owner, Parser.Node returnTerm) {
        Scope sc = new Scope(parent, level, owner);

        if (params != null) {
            for (Parser.Node leaf : params) declareVar(sc, leaf, SymKind.PARAMETER);
        }
        for (Parser.Node leaf : nameLeaves(inner(p, "V_DECL"))) declareVar(sc, leaf, SymKind.VARIABLE);

        List<Parser.Node> ftypes = new ArrayList<>();
        collectFTypes(inner(p, "F_DECL"), ftypes);
        List<Symbol> fsyms = new ArrayList<>();
        for (Parser.Node ft : ftypes) fsyms.add(declareFunc(sc, ft));

        for (int i = 0; i < ftypes.size(); i++) {
            Parser.Node ft = ftypes.get(i);
            Parser.Node term = innerOrNull(ft, "TERM");   //only num functions have a return TERM
            analyzeP(inner(ft, "P"), sc, level + 1,
                     nameLeaves(inner(ft, "V_DECL")), fsyms.get(i), term);
        }

        resolveUses(inner(p, "ALGO"), sc);
        if (returnTerm != null) resolveUses(returnTerm, sc);
    }

    //V2 + V3
    private void declareVar(Scope sc, Parser.Node leaf, SymKind kind) {
        String name = leaf.contents;
        Symbol old = sc.vars.get(name);
        if (old != null) {
            String why;
            if (old.kind == SymKind.PARAMETER && kind == SymKind.VARIABLE) {
                why = "the local variable '" + name + "' masks the parameter '" + name
                    + "' of the same function (declared at " + loc(old.declNodeId) + ")";
            } else if (old.kind == SymKind.PARAMETER) {
                why = "the parameter '" + name + "' is listed twice (first at " + loc(old.declNodeId) + ")";
            } else {
                why = "the variable '" + name + "' is declared twice at scope level " + sc.level
                    + " (first at " + loc(old.declNodeId) + ")";
            }
            throw new SemanticException("Semantic error at " + at(leaf) + ": " + why + ".");
        }
        Symbol s = newSymbol(name, kind, sc, leaf);
        sc.vars.put(name, s);
    }

    //F2
    private Symbol declareFunc(Scope sc, Parser.Node ftype) {
        Parser.Node leaf = nameLeaf(ftype);
        String name = leaf.contents;
        Symbol old = sc.funcs.get(name);
        if (old != null) {
            throw new SemanticException("Semantic error at " + at(leaf) + ": the function '" + name
                + "' is declared twice at scope level " + sc.level + " (first at " + loc(old.declNodeId)
                + "); function names must be unique, SPL has no overloading.");
        }
        Symbol s = newSymbol(name, SymKind.FUNCTION, sc, leaf);
        sc.funcs.put(name, s);
        return s;
    }

    private Symbol newSymbol(String orig, SymKind kind, Scope sc, Parser.Node leaf) {
        String owner = sc.owner == null ? "-" : sc.owner.sysName;
        //for a function symbol sc is the scope that CONTAINS it, so owner = the enclosing function. fine.
        Symbol s = new Symbol("sys" + (nextSys++), orig, kind, sc.level, leaf.id, owner);
        table.symbols.add(s);
        table.byNode.put(leaf.id, s);
        declLeaves.put(leaf.id, leaf);
        return s;
    }

    /*
    walks an ALGO (or a return TERM) and resolves every name leaf it finds.
    block structure (if/else/while/do bodies) does not create scopes in SPL, only P does, so a
    plain recursive walk is enough, and it never enters a nested P (those only hang under F_TYPE).
    the parent of a name leaf tells what kind of use it is:
      CALL            => function name   (F1)
      ASSIGN / TERM   => variable name   (V1)
    */
    private void resolveUses(Parser.Node n, Scope sc) {
        for (Parser.Node c : n.children) {
            if (!c.isLeaf) {
                resolveUses(c, sc);
            } else if (isName(c)) {
                if (n.contents.equals("CALL")) resolveCall(c, sc);
                else resolveVar(c, sc);
            }
        }
    }

    //V1: nearest declaration on the ancestor chain
    private void resolveVar(Parser.Node leaf, Scope sc) {
        for (Scope s = sc; s != null; s = s.parent) {
            Symbol sym = s.vars.get(leaf.contents);
            if (sym != null) {
                table.byNode.put(leaf.id, sym);
                return;
            }
        }
        throw new SemanticException("Semantic error at " + at(leaf) + ": the variable '" + leaf.contents
            + "' is used here but is not declared in any V_DECL at scope level " + sc.level
            + " or in any enclosing scope above it.");
    }

    //F1: only the immediate F_DECL of this very P
    private void resolveCall(Parser.Node leaf, Scope sc) {
        Symbol sym = sc.funcs.get(leaf.contents);
        if (sym != null) {
            table.byNode.put(leaf.id, sym);
            return;
        }
        String hint = "";
        for (Scope s = sc.parent; s != null; s = s.parent) {
            if (s.funcs.containsKey(leaf.contents)) {
                Symbol other = s.funcs.get(leaf.contents);
                hint = " A function named '" + leaf.contents + "' exists at scope level " + s.level
                     + " (declared at " + loc(other.declNodeId) + "), but a function may only be called from"
                     + " the scope that declares it: no recursion, no calls to sibling, parent or"
                     + " grandparent functions.";
                break;
            }
        }
        throw new SemanticException("Semantic error at " + at(leaf) + ": the function '" + leaf.contents
            + "' is called here but is not declared in the F_DECL at scope level " + sc.level + "." + hint);
    }

    //tree helpers

    private static boolean isName(Parser.Node n) {
        return n.isLeaf && n.contents.startsWith("#");
    }

    private static Parser.Node innerOrNull(Parser.Node n, String contents) {
        for (Parser.Node c : n.children)
            if (!c.isLeaf && c.contents.equals(contents)) return c;
        return null;
    }

    private static Parser.Node inner(Parser.Node n, String contents) {
        Parser.Node c = innerOrNull(n, contents);
        if (c == null)
            throw new IllegalStateException("malformed syntax tree: <" + n.contents + "> has no <" + contents + "> child");
        return c;
    }

    //V_DECL -> NAME V_DECL | epsilon  ==> the NAME leaves in order
    private static List<Parser.Node> nameLeaves(Parser.Node vdecl) {
        List<Parser.Node> out = new ArrayList<>();
        for (Parser.Node v = vdecl; v != null; v = innerOrNull(v, "V_DECL")) {
            for (Parser.Node c : v.children)
                if (isName(c)) out.add(c);
        }
        return out;
    }

    //F_DECL -> F_TYPE F_DECL | epsilon  ==> the F_TYPE nodes in order
    private static void collectFTypes(Parser.Node fdecl, List<Parser.Node> out) {
        for (Parser.Node f = fdecl; f != null; f = innerOrNull(f, "F_DECL")) {
            Parser.Node ft = innerOrNull(f, "F_TYPE");
            if (ft != null) out.add(ft);
        }
    }

    //F_TYPE -> (void|num) NAME ( V_DECL ) { P return [( TERM )] }  ==> the NAME leaf (always child 1)
    private static Parser.Node nameLeaf(Parser.Node ftype) {
        for (Parser.Node c : ftype.children)
            if (isName(c)) return c;
        throw new IllegalStateException("malformed syntax tree: <F_TYPE> without a name");
    }

    //error-message helpers

    private static String at(Parser.Node leaf) {
        return "line " + leaf.line + ", column " + leaf.col;
    }

    //position of an earlier declaration (for "first declared at ..." in messages)
    private String loc(int nodeId) {
        Parser.Node leaf = declLeaves.get(nodeId);
        return leaf == null ? "an earlier line" : at(leaf);
    }

    //output

    /** human-readable symbol table: all declared entities, then the leaf-id -> sys-name map. */
    public static void writeSymbolTable(SymbolTable t, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("SYMBOL TABLE\n");
        sb.append(String.format("%-8s %-10s %-12s %-6s %-9s %s%n",
                "sysName", "kind", "original", "level", "declNode", "inFunction"));
        for (Symbol s : t.symbols) {
            sb.append(String.format("%-8s %-10s %-12s %-6d %-9d %s%n",
                    s.sysName, s.kind, s.origName, s.scopeLevel, s.declNodeId, s.ownerSysName));
        }
        sb.append("\nNODE MAP (id of every user-defined-name leaf in tree.xml -> sys name)\n");
        for (Map.Entry<Integer, Symbol> e : t.byNode.entrySet()) {
            sb.append(String.format("%-8d -> %-8s (%s)%n", e.getKey(), e.getValue().sysName, e.getValue().origName));
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            w.print(sb);
        }
    }

    /** same format as tree.xml, but every user-defined-name leaf carries its sys name instead of '#name'. */
    public static void writeRenamedTree(Parser.Node root, SymbolTable t, String path) throws IOException {
        List<Parser.Node> all = new ArrayList<>();
        preorder(root, all);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tree>\n");
        for (Parser.Node n : all) {
            Symbol s = t.byNode.get(n.id);
            String text = (n.isLeaf && s != null) ? s.sysName : n.contents;
            sb.append("  <node id=\"").append(n.id).append("\">\n");
            sb.append("    <contents>").append(esc(text)).append("</contents>\n");
            if (!n.isLeaf) {
                sb.append("    <children>");
                for (int i = 0; i < n.children.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(n.children.get(i).id);
                }
                sb.append("</children>\n");
            }
            if (n.parent != null) sb.append("    <parent>").append(n.parent.id).append("</parent>\n");
            sb.append("  </node>\n");
        }
        sb.append("</tree>\n");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            w.print(sb);
        }
    }

    private static void preorder(Parser.Node n, List<Parser.Node> out) {
        out.add(n);
        for (Parser.Node c : n.children) preorder(c, out);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    //driver

    /*
    java SemanticAnalyzer [path]   (default SPL.txt)
    lexer -> parser (tree.xml as in phase 1) -> this analysis.
    exit codes like Parser: 0 fine (symtable.txt + tree_renamed.xml written),
    1 = the SPL program is wrong (lexical, syntax OR semantic error, message on stderr), 2 = io problem.
    */
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "SPL.txt";
        try {
            Lexer.TokenStream ts = Lexer.streamFromFile(path);
            Parser parser = new Parser(ts);
            Parser.Node root = parser.parseProgram();
            parser.writeXml(root, "tree.xml");
            SymbolTable table = analyze(root);
            writeSymbolTable(table, "symtable.txt");
            writeRenamedTree(root, table, "tree_renamed.xml");
            System.out.println("OK: '" + path + "' is semantically correct. " + table.symbols.size()
                + " symbol(s). Wrote symtable.txt and tree_renamed.xml.");
        } catch (Lexer.LexException | Parser.ParseException | SemanticException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Cannot read/write: " + e.getMessage());
            System.exit(2);
        }
    }
}