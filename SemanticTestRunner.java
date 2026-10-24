import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * In-process tests for SemanticAnalyzer (phase 2a).
 *
 *  1. VALID programs must analyse without error, and (where given) the renaming pattern of one
 *     name must match: e.g. "0,1,1,0" = four leaves called #x, 1st and 4th are the same entity,
 *     2nd and 3rd are another one.
 *  2. INVALID programs must throw SemanticException whose message contains "line N, column N"
 *     and a given keyword.
 *  3. FUZZ: random programs from ProgramGen must either pass or throw one of the three expected
 *     exception types, never crash with anything else; and every accepted program must satisfy
 *     the renaming invariants (checked by brute force).
 *
 * usage: java SemanticTestRunner [fuzzCount]
 */
public final class SemanticTestRunner {

    private static final Pattern LOC = Pattern.compile("line \\d+, column \\d+");
    private static int bad = 0, ran = 0;

    //Announcement #21 skeleton. f(1) > g(2), h(2); g > j(3); h > k(3). x declared in g, used in j and k.
    private static String announcement(String mainDecl, String hDecl, String kUse) {
        return mainDecl + " : "
            + "num #f ( ) { : "
            +   "num #g ( ) { #x : num #j ( ) { #r : : #r = #x ; return ( #r ) } : return ( 0 ) } "
            +   "num #h ( ) { " + hDecl + " : num #k ( ) { #r : : #r = " + kUse + " ; return ( #r ) } : return ( 0 ) } "
            +   ": return ( 0 ) } : nop ;";
    }

    public static void main(String[] args) throws Exception {
        int fuzz = args.length > 0 ? Integer.parseInt(args[0]) : 500;

        //valid programs
        valid("empty program", ": : ", "#x", null);
        valid("Announcement #21: x in j -> g, x in k -> main  (blue/red)",
              announcement("#x", "", "#x"), "#x", "0,1,1,0");
        // the leaves called #x in source order: main decl, g decl, j use, k use  -> A B B A
        valid("shadowing by parameter",
              "#x : num #f ( #x ) { : : return ( #x ) } : #x = 5 ;", "#x", "0,1,1,0");
        valid("same name in two sibling functions = two entities",
              ": void #a ( #p ) { : : #p = 1 ; return } void #b ( #p ) { : : #p = 2 ; return } : nop ;",
              "#p", "0,0,1,1");
        valid("local of inner function hides outer local",
              "#x : void #f ( ) { #x : : #x = 1 ; return } : #x = 2 ;", "#x", "0,1,1,0");
        valid("inner function uses variable of grandparent",
              "#x : void #f ( ) { : void #g ( ) { : : #x = 1 ; return } : #g ( ) ; return } : #f ( ) ;",
              "#x", "0,0");
        valid("call in return term and in arguments",
              ": num #f ( #a ) { : num #g ( ) { : : return ( 1 ) } : return ( add ( #g ( ) #a ) ) } : print ( #f ( #f ( 1 ) ) ) ;",
              "#a", "0,0");
        valid("same function name in different scopes is fine",
              ": void #f ( ) { : void #h ( ) { : : nop ; return } : #h ( ) ; return } "
            + "void #g ( ) { : void #h ( ) { : : nop ; return } : #h ( ) ; return } : #f ( ) ; #g ( ) ;",
              "#h", "0,0,1,1");
        valid("variable and function may share a user name (separate namespaces)",
              "#a : void #a ( ) { : : nop ; return } : #a = 1 ; #a ( ) ;", "#a", "0,1,0,1");
        valid("names inside nested if / while / do bodies",
              "#i : : while lesser ( #i 3 ) do { if eq ( #i 1 ) then { #i = 2 ; } else { do { #i = 3 ; } until larger ( #i 2 ) ; } ; } ;",
              "#i", "0,0,0,0,0,0");

        //invalid programs
        invalid("undeclared variable in main",            ": : #x = 1 ;", "variable '#x'");
        invalid("undeclared variable in return term",     ": num #f ( ) { : : return ( #z ) } : nop ;", "variable '#z'");
        invalid("Announcement #21: x not visible sideways", announcement("", "", "#x"), "variable '#x'");
        invalid("double declaration in main",             "#a #b #a : : nop ;", "declared twice");
        invalid("double declaration in function body",    ": void #f ( ) { #a #a : : return } : nop ;", "declared twice");
        invalid("local masks parameter",                  ": num #f ( #a ) { #a : : return ( #a ) } : nop ;", "masks the parameter");
        invalid("same parameter twice",                   ": void #f ( #a #a ) { : : return } : nop ;", "listed twice");
        invalid("function declared twice (overloading)",  ": void #f ( ) { : : return } void #f ( #a ) { : : return } : nop ;", "function '#f' is declared twice");
        invalid("call of undeclared function",            ": : #nope ( ) ;", "function '#nope'");
        invalid("direct recursion",                       ": void #f ( ) { : : #f ( ) ; return } : #f ( ) ;", "no recursion");
        invalid("indirect recursion / sibling call",
                ": void #f ( ) { : : #g ( ) ; return } void #g ( ) { : : #f ( ) ; return } : nop ;", "function '#g'");
        invalid("main calls a grandchild function",
                ": void #f ( ) { : void #g ( ) { : : nop ; return } : #g ( ) ; return } : #g ( ) ;", "function '#g'");
        invalid("child calls function of parent scope",
                ": void #h ( ) { : : nop ; return } void #f ( ) { : void #g ( ) { : : #h ( ) ; return } : nop ; return } : nop ;",
                "function '#h'");
        invalid("function used as plain variable",        ": void #f ( ) { : : return } : #f = 1 ;", "variable '#f'");

        System.out.println();
        fuzz(fuzz);

        System.out.println(bad == 0 ? "ALL " + ran + " SEMANTIC TESTS PASSED" : bad + " of " + ran + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    //helpers

    private static Parser.Node parse(String src) {
        return new Parser(new Lexer.TokenStream(Lexer.tokenize(src + "\n"))).parseProgram();
    }

    private static void valid(String name, String src, String watch, String expectedPattern) {
        ran++;
        try {
            Parser.Node root = parse(src);
            SemanticAnalyzer.SymbolTable t = SemanticAnalyzer.analyze(root);
            if (expectedPattern != null) {
                String got = pattern(root, t, watch);
                if (!got.equals(expectedPattern)) {
                    fail(name, "renaming pattern for " + watch + " is " + got + ", expected " + expectedPattern);
                    return;
                }
            }
            System.out.println("ok   valid   : " + name);
        } catch (RuntimeException e) {
            fail(name, "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void invalid(String name, String src, String keyword) {
        ran++;
        try {
            SemanticAnalyzer.analyze(parse(src));
            fail(name, "accepted, expected a semantic error");
        } catch (SemanticAnalyzer.SemanticException e) {
            if (!LOC.matcher(e.getMessage()).find()) fail(name, "no line/column in: " + e.getMessage());
            else if (!e.getMessage().contains(keyword)) fail(name, "message lacks '" + keyword + "': " + e.getMessage());
            else System.out.println("ok   invalid : " + name);
        } catch (RuntimeException e) {
            fail(name, "wrong exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void fail(String name, String why) {
        bad++;
        System.out.println("FAIL           : " + name + "\n                 " + why);
    }

    //leaves with the given text in source order, as first-appearance-normalised sys-name numbers
    private static String pattern(Parser.Node root, SemanticAnalyzer.SymbolTable t, String watch) {
        List<Parser.Node> leaves = new ArrayList<>();
        collectLeaves(root, watch, leaves);
        Map<String, Integer> seen = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (Parser.Node l : leaves) {
            String sys = t.byNode.get(l.id).sysName;
            Integer k = seen.get(sys);
            if (k == null) { k = seen.size(); seen.put(sys, k); }
            if (sb.length() > 0) sb.append(",");
            sb.append(k);
        }
        return sb.toString();
    }

    private static void collectLeaves(Parser.Node n, String text, List<Parser.Node> out) {
        if (n.isLeaf && n.contents.equals(text)) out.add(n);
        for (Parser.Node c : n.children) collectLeaves(c, text, out);
    }

    //fuzz

    private static void fuzz(int count) {
        int accepted = 0, rejected = 0, syntaxOrLex = 0;
        for (int i = 0; i < count; i++) {
            // small name alphabet so that random programs have a decent chance of being semantically fine
            String src = new ProgramGen(1000 + i, 2).genProgram();
            ran++;
            Parser.Node root;
            try {
                root = parse(src);
            } catch (Lexer.LexException | Parser.ParseException e) {
                syntaxOrLex++;
                continue;
            }
            try {
                SemanticAnalyzer.SymbolTable t = SemanticAnalyzer.analyze(root);
                accepted++;
                String err = invariants(root, t);
                if (err != null) fail("fuzz #" + i, err + "\n   program: " + src);
            } catch (SemanticAnalyzer.SemanticException e) {
                rejected++;
                if (!LOC.matcher(e.getMessage()).find()) fail("fuzz #" + i, "no location: " + e.getMessage());
            } catch (RuntimeException e) {
                fail("fuzz #" + i, "CRASH " + e + "\n   program: " + src);
            }
        }
        System.out.println("fuzz: " + count + " programs -> " + accepted + " accepted, " + rejected
            + " semantic errors, " + syntaxOrLex + " not even parseable (generator quirk)");
    }

    //invariants of any accepted program
    private static String invariants(Parser.Node root, SemanticAnalyzer.SymbolTable t) {
        List<Parser.Node> names = new ArrayList<>();
        allNames(root, names);
        java.util.Set<String> sysNames = new java.util.HashSet<>();
        for (SemanticAnalyzer.Symbol s : t.symbols)
            if (!sysNames.add(s.sysName)) return "duplicate sys name " + s.sysName;
        for (Parser.Node l : names) {
            SemanticAnalyzer.Symbol s = t.byNode.get(l.id);
            if (s == null) return "name leaf " + l.id + " (" + l.contents + ") has no symbol";
            if (!s.origName.equals(l.contents)) return "leaf " + l.contents + " mapped to symbol of " + s.origName;
        }
        if (t.byNode.size() != names.size()) return "node map size differs from number of name leaves";
        return null;
    }

    private static void allNames(Parser.Node n, List<Parser.Node> out) {
        if (n.isLeaf && n.contents.startsWith("#")) out.add(n);
        for (Parser.Node c : n.children) allNames(c, out);
    }
}