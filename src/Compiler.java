import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/*
the complete SPL compiler: lexer -> parser -> 2a names/scopes -> 2b types -> 3a/3b code generation.

usage:  java -jar group-7.jar [input.txt] [output.cpp]        (defaults: SPL.txt, output.cpp)

exit codes (same convention as the front end):
  0  compiled, output file written, nothing printed on stderr
  1  the SPL program is wrong: lexical, syntax, naming/scope or type error. one message on stderr, NO output file.
  2  the input file could not be read or an output file could not be written, or nesting too deep

besides the C++ file, a successful run also writes the intermediate results for inspection:
  tree.xml (syntax tree), tree_renamed.xml (names replaced by the unique system names), symtable.txt (symbol table + types)
*/
public final class Compiler {

    /** everything a successful compilation produces. */
    public static final class Output {
        public Parser.Node root;
        public SemanticAnalyzer.SymbolTable symbols;
        public TypeAnalyzer.Result types;
        public String cpp;
    }

    /** any error that is the fault of the SPL program (as opposed to an io problem). */
    public static final class CompileException extends RuntimeException {
        public final String phase;
        public CompileException(String phase, String msg) { super(msg); this.phase = phase; }
    }

    /** compiles SPL source text. trailing newline is added so a last token without blank still lexes. */
    public static Output compile(String src) {
        Output o = new Output();
        try {
            Parser parser = new Parser(new Lexer.TokenStream(Lexer.tokenize(src)));
            o.root = parser.parseProgram();
        } catch (Lexer.LexException e) {
            throw new CompileException("lexical", e.getMessage());
        } catch (Parser.ParseException e) {
            throw new CompileException("syntax", e.getMessage());
        }
        try {
            o.symbols = SemanticAnalyzer.analyze(o.root);
        } catch (SemanticAnalyzer.SemanticException e) {
            throw new CompileException("semantic", e.getMessage());
        }
        try {
            o.types = TypeAnalyzer.analyze(o.root, o.symbols);
        } catch (TypeAnalyzer.TypeException e) {
            throw new CompileException("type", e.getMessage());
        }
        try {
            o.cpp = CodeGenerator.generate(o.root, o.symbols, o.types);
        } catch (CodeGenerator.CodeGenException e) {
            throw new CompileException("codegen", "Internal code generation error: " + e.getMessage());
        }
        return o;
    }

    public static void main(String[] args) {
        //the tree is nested as deep as the program is long, so run in a thread with a big stack (as Parser.main does)
        final int[] rc = {0};
        Thread t = new Thread(null, () -> rc[0] = run(args), "compiler", 1L << 29);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            rc[0] = 2;
        }
        System.exit(rc[0]);
    }

    private static int run(String[] args) {
        String in = args.length > 0 ? args[0] : "SPL.txt";
        String out = args.length > 1 ? args[1] : "output.cpp";
        String src;
        try {
            src = new String(Files.readAllBytes(Paths.get(in)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot read '" + in + "': " + e.getMessage());
            return 2;
        }
        try {
            Files.deleteIfExists(Paths.get(out));   //never leave an old result behind that could be mistaken for this run's
            Output o = compile(src);
            //intermediate results (phase 1 + 2 artefacts) first, the C++ file last
            new Parser(new Lexer.TokenStream(Lexer.tokenize(src))).writeXml(o.root, "tree.xml");
            SemanticAnalyzer.writeSymbolTable(o.symbols, "symtable.txt");
            Files.write(Paths.get("symtable.txt"), TypeAnalyzer.describe(o.types, o.symbols).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            SemanticAnalyzer.writeRenamedTree(o.root, o.symbols, "tree_renamed.xml");
            Files.write(Paths.get(out), o.cpp.getBytes(StandardCharsets.UTF_8));
            System.out.println("OK: '" + in + "' compiled to '" + out + "' (all numbers are " + o.types.typ() + ").");
            return 0;
        } catch (CompileException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("Cannot write output: " + e.getMessage());
            return 2;
        } catch (StackOverflowError e) {
            System.err.println("Program is nested too deeply for the compiler to handle.");
            return 2;
        } catch (OutOfMemoryError e) {
            System.err.println("Out of memory: the program is too large (function inlining multiplies nested calls).");
            return 2;
        } catch (RuntimeException e) {
            System.err.println("Internal compiler error: " + e);
            return 2;
        }
    }
}
