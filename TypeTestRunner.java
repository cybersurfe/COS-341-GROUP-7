import java.util.regex.Pattern;

/**
 * In-process tests for TypeAnalyzer (phase 2b).
 *  1. VALID programs must type-check and produce the expected isFloat.
 *  2. INVALID programs must throw TypeException with "line N, column N" and a keyword.
 *  3. FUZZ: random programs either type-check or fail gracefully; never crash.
 *
 * usage: java TypeTestRunner [fuzzCount]   (default 500)
 */
public final class TypeTestRunner {

    private static final Pattern LOC = Pattern.compile("line \\d+, column \\d+");
    private static int bad = 0, ran = 0;

    public static void main(String[] args) throws Exception {
        int fuzz = args.length > 0 ? Integer.parseInt(args[0]) : 500;

        // ---- VALID: (name, source, expected isFloat) ----
        valid("empty program",                       ": : ", false);
        valid("int basic",                           "#x : : #x = 5 ; print ( #x ) ;", false);
        valid("decimal NUM -> float",
              "#x : : #x = 5.5 ; print ( #x ) ;", true);
        valid("div -> float",
              ": : print ( div ( 10 4 ) ) ;", true);
        valid("mod only -> int",
              ": : print ( mod ( 10 3 ) ) ;", false);
        valid("div + decimal still float",
              "#x : : #x = div ( 1.5 2.5 ) ;", true);
        valid("add only -> int",
              ": : print ( add ( 1 2 ) ) ;", false);
        valid("num function call in assign",
              "#x : num #f ( #a ) { : : return ( #a ) } : #x = #f ( 5 ) ;", false);
        valid("void function as INSTR",
              ": void #f ( #a ) { : : return } : #f ( 5 ) ;", false);
        valid("num function as INSTR (return discarded)",
              ": num #f ( #a ) { : : return ( #a ) } : #f ( 5 ) ;", false);
        valid("if with boolean condition",
              ": : if eq ( 1 2 ) then { nop ; } else { nop ; } ;", false);
        valid("while with boolean",
              ": : while lesser ( 1 2 ) do { nop ; } ;", false);
        valid("and/or/not",
              ": : if and ( eq ( 1 1 ) not ( larger ( 1 2 ) ) ) then { nop ; } else { nop ; } ;", false);
        valid("nested functions, all internal",
              "#x : num #f ( #a ) { #y : num #g ( #p ) { : : return ( add ( #p 1 ) ) } : #y = #g ( #a ) ; return ( mul ( #y 2 ) ) } : #x = #f ( 5 ) ;", false);
        valid("num function returning float via div",
              "#x : num #f ( ) { : : return ( div ( 10 4 ) ) } : #x = #f ( ) ;", true);

        // ---- INVALID: (name, source, keyword in error message) ----
        invalid("mod + div",
                ": : print ( mod ( 10 3 ) ) ; print ( div ( 10 3 ) ) ;",
                "A float-integer-conflict might perhaps be possible");
        invalid("mod + decimal NUM",
                ": : print ( mod ( 10 3 ) ) ; print ( 1.5 ) ;",
                "decimal point");
        invalid("mod with decimal argument",
                "#x : : #x = mod ( 1.5 2 ) ;",
                "decimal point");
        invalid("void function used as ASSIGN RHS",
                "#x : void #f ( ) { : : return } : #x = #f ( ) ;",
                "procedure");
        invalid("void function used as print argument",
                ": void #f ( ) { : : return } : print ( #f ( ) ) ;",
                "procedure");
        invalid("void function used as arithmetic operand",
                "#x : void #f ( ) { : : return } : #x = add ( #f ( ) 5 ) ;",
                "procedure");
        invalid("void function used as num-return value",
                "#x : num #f ( ) { : void #h ( ) { : : return } : return ( #h ( ) ) } : #x = #f ( ) ;",
                "procedure");
        invalid("void function used as comparison operand",
                ": void #f ( ) { : : return } : if eq ( #f ( ) 5 ) then { nop ; } else { nop ; } ;",
                "procedure");
        invalid("void function used as call argument",
                "#x : void #f ( ) { : : return } num #g ( #a ) { : : return ( #a ) } : #x = #g ( #f ( ) ) ;",
                "procedure");

        System.out.println();
        fuzz(fuzz);

        System.out.println(bad == 0
            ? "ALL " + ran + " TYPE TESTS PASSED"
            : bad + " of " + ran + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static Parser.Node parse(String src) {
        return new Parser(new Lexer.TokenStream(Lexer.tokenize(src + "\n"))).parseProgram();
    }

    private static void valid(String name, String src, boolean expectedFloat) {
        ran++;
        try {
            Parser.Node root = parse(src);
            SemanticAnalyzer.SymbolTable t = SemanticAnalyzer.analyze(root);
            TypeAnalyzer.Result r = TypeAnalyzer.analyze(root, t);
            if (r.isFloat != expectedFloat) {
                fail(name, "isFloat=" + r.isFloat + ", expected " + expectedFloat);
                return;
            }
            System.out.println("ok   valid   : " + name);
        } catch (RuntimeException e) {
            fail(name, "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void invalid(String name, String src, String keyword) {
        ran++;
        try {
            Parser.Node root = parse(src);
            SemanticAnalyzer.SymbolTable t = SemanticAnalyzer.analyze(root);
            TypeAnalyzer.analyze(root, t);
            fail(name, "accepted, expected a type error");
        } catch (TypeAnalyzer.TypeException e) {
            if (!LOC.matcher(e.getMessage()).find())
                fail(name, "no line/column in: " + e.getMessage());
            else if (!e.getMessage().contains(keyword))
                fail(name, "message lacks '" + keyword + "': " + e.getMessage());
            else
                System.out.println("ok   invalid : " + name);
        } catch (RuntimeException e) {
            fail(name, "wrong exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void fail(String name, String why) {
        bad++;
        System.out.println("FAIL           : " + name + "\n                 " + why);
    }

    private static void fuzz(int count) {
        int ok = 0, typeErr = 0, notValid = 0;
        for (int i = 0; i < count; i++) {
            String src = new ProgramGen(2000 + i, 2).genProgram();
            ran++;
            Parser.Node root;
            try { root = parse(src); }
            catch (Lexer.LexException | Parser.ParseException e) { notValid++; continue; }
            SemanticAnalyzer.SymbolTable t;
            try { t = SemanticAnalyzer.analyze(root); }
            catch (SemanticAnalyzer.SemanticException e) { notValid++; continue; }
            try {
                TypeAnalyzer.Result r = TypeAnalyzer.analyze(root, t);
                ok++;
                if (r.hasMod && (r.hasDiv || r.hasDecimalNum))
                    fail("fuzz #" + i, "hasMod with hasDiv/hasDecimalNum survived firstPass");
                boolean expected = !r.hasMod && (r.hasDiv || r.hasDecimalNum);
                if (r.isFloat != expected)
                    fail("fuzz #" + i, "isFloat=" + r.isFloat + " but expected " + expected);
            } catch (TypeAnalyzer.TypeException e) {
                typeErr++;
                if (!LOC.matcher(e.getMessage()).find())
                    fail("fuzz #" + i, "no location: " + e.getMessage());
            } catch (RuntimeException e) {
                fail("fuzz #" + i, "CRASH " + e + "\n   program: " + src);
            }
        }
        System.out.println("fuzz: " + count + " programs -> "
            + ok + " type-ok, " + typeErr + " type errors, "
            + notValid + " not parseable/semantically invalid");
    }
}