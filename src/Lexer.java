import java.io.IOException; //throws if SPL.txt cant be read
import java.nio.charset.StandardCharsets; //const definitions for the standard Charsets; read the file as UTF-8
import java.nio.file.Files; //static methods for manipulating files and directories
import java.nio.file.Paths; //files rely on a path object; converts a path string into a path instance
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern; //regex stuff lke text search, matching and replacement operations

/*
every token ends with a blank so i split the input into whitespace separated chunks
and classify each chunk as 1 token.
the EOF token "$" is generated here BUT it is not present in the SPL.txt

made it compatible with java 11+
*/

/*
the lexers job is to read the raw text of SPL.txt and turn it into a list of tokens.
the parser will then workk on this list of tokens 
*/

public final class Lexer {
    //token kinds => category of a token
    
    public enum Kind { NUM, NAME, STRING, KEYWORD, SYMBOL, EOF }

    //token class => one token produced by the lexer. Its immutable therefore final is used
    //this is so that the parser can never accidently change it
    public static final class Token {
        public final Kind kind;
        public final String exactText;
        public final int line;
        public final int col;

        public Token(Kind kind, String exactText, int line, int col) {
            this.kind = kind;
            this.exactText = exactText;
            this.line = line;
            this.col = col;
        }

        /*
        returns the name of this token as a terminal token of the SPL grammar
        convenient for LL(1) parsing table, where cols are terminals:
        keywords and symbols are their own text, while names, nums ans strings are repped by their own category
        */
       public String terminals() {
        if (kind == Kind.NUM) {
            return "NUM";
        }
        if (kind == Kind.NAME) {
            return "USER-DEFINED-NAME";
        }
        if (kind == Kind.STRING) {
            return "STRING";
        }
        if (kind == Kind.EOF) {
            return "$";
        }
        return exactText;
       }

       @Override 
       public String toString() {
        return String.format("%-8s %-20s (line %d, col %d)", kind, exactText, line, col);
       }
    }

    //the error class => thrown when input contains non valid SPL token
    public static final class LexException extends RuntimeException {
        public final int line, col; //problem line
        public LexException(String msg, int line, int col) {
            super("Lexical error at line " + line + ", column " + col + ": " + msg);
            this.line = line;
            this.col = col;
        }
    }

    //definitions of tokesn from SPL syntax spec

    //keywords: reserved words
    private static final Set<String> KEYWORDS = Set.of(
        "void", "num", "return",
        "print", "nop", "comment",
        "if", "then", "else",
        "do", "while", "until",
        "not", "and", "or", "eq", "larger", "lesser",
        "mod", "add", "sub", "mul", "div", "neg"
    );

    //symbols of grammer
    private static final Set<String> SYMBOLS = Set.of(
        "(", ")", "{", "}", ":", ";", "="
    );

    //numbers accepted => this accounts for neg and pos numbers, floats and ints
    private static final Pattern NUMS = Pattern.compile(
        "0" 
        + "|-?0\\.[0-9]*[1-9]" 
        + "|-?[1-9][0-9]*\\.[0-9]*[1-9]"
        + "|-?[1-9][0-9]*"
    );

    //names => these are like variables defined by the user, we want to not confuse this with any reserved words
    private static final Pattern NAME = Pattern.compile("#[a-z0-9]*");

    //strings
    private static final Pattern STRING = Pattern.compile("\"[,.:?!0-9a-z\\-]*\"");

    //enforce trailing blank after final token
    private static final boolean STRICT_EOF_BLANK = false;

    //accept en dash, normalise to '-'
    private static final char EN_DASH = '\u2013';

    //public entry points

    //reads file as UTF-8 and tokenises
    public static List<Token> tokenizeFile(String path) throws IOException {
        String src = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return tokenize(src);
    }

    /* walks input once; blanks end a chunk; each chunk = one token; list ends with EOF. */
    public static List<Token> tokenize(String src) {
        if (!src.isEmpty() && src.charAt(0) == '\uFEFF') {
            src = src.substring(1); //strip BOM
        }

        List<Token> out = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int line = 1, col = 1;
        int startLine = 1, startCol = 1;
        int i = 0, n = src.length();

        while (i < n) {
            char c = src.charAt(i);

            if (isBlankChar(c)) {
                //blank ends the current chunk (if any) => that chunk is one token
                if (chunk.length() > 0) {
                    out.add(classify(chunk.toString(), startLine, startCol));
                    chunk.setLength(0);
                }
                if (c == '\n') {
                    line++;
                    col = 1;
                    i++;
                } else if (c == '\r') {
                    i++;
                    if (i < n && src.charAt(i) == '\n') {
                        i++; //CRLF = one line break
                    }
                    line++;
                    col = 1;
                } else {
                    col++; //space or tab
                    i++;
                }
            } else {
                if (chunk.length() == 0) {
                    startLine = line;
                    startCol = col;
                }
                chunk.append(c);
                col++;
                i++;
            }
        }

        //last token has no trailing blank
        if (chunk.length() > 0) {
            if (STRICT_EOF_BLANK) {
                throw new LexException("Token '" + chunk + "' at end of file must be followed by a blank space.",
                                       startLine, startCol);
            }
            out.add(classify(chunk.toString(), startLine, startCol));
        }

        out.add(new Token(Kind.EOF, "$", line, col));
        return out;
    }

    //helpers

    //blank chars: space, LF, CR, tab
    private static boolean isBlankChar(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    //classify chunk, throw LexException if unknown
    private static Token classify(String raw, int line, int col) {
        String s = raw.replace(EN_DASH, '-');

        //categories are disjoint; keywords first as cheapest
        if (KEYWORDS.contains(s)) {
            return new Token(Kind.KEYWORD, s, line, col);
        }
        if (SYMBOLS.contains(s)) {
            return new Token(Kind.SYMBOL, s, line, col);
        }
        if (NAME.matcher(s).matches()) {
            return new Token(Kind.NAME, s, line, col);
        }
        if (STRING.matcher(s).matches()) {
            return new Token(Kind.STRING, s, line, col);
        }
        if (NUMS.matcher(s).matches()) {
            return new Token(Kind.NUM, s, line, col);
        }

        throw new LexException("Invalid token '" + raw + "'. " + hintFor(s), line, col);
    }

    private static boolean containsSymbolChar(String s) {
        for (int k = 0; k < s.length(); k++) {
            if ("(){}:;=".indexOf(s.charAt(k)) >= 0) {
                return true;
            }
        }
        return false;
    }

    //hint for an unrecognised chunk
    private static String hintFor(String s) {
        char first = s.charAt(0);

        //user-defined name attempt
        if (first == '#') {
            if (containsSymbolChar(s)) {
                return "Every token must be followed by a blank space, e.g. write '( #x )' not '(#x)'.";
            }
            return "Names must be '#' followed only by lowercase letters a-z and digits 0-9.";
        }

        //string attempt
        if (first == '"') {
            if (s.length() < 2 || s.charAt(s.length() - 1) != '"') {
                return "Strings must end with a closing '\"' and may not contain spaces (a space ends the token).";
            }
            return "Strings may only contain , . : - ? ! digits and lowercase letters a-z "
                 + "(no spaces, no uppercase, no other punctuation).";
        }

        //number attempt
        if (s.matches("-?[0-9.]+")) {
            return numberHint(s);
        }
        if (first == '+') {
            return "SPL numbers have no '+' sign; write positive numbers without a sign.";
        }

        //keywords / names written wrongly
        if (KEYWORDS.contains(s.toLowerCase())) {
            return "Keywords must be written in lowercase.";
        }
        if (s.matches("[a-z0-9]+")) {
            return "Unknown word. User-defined names must start with '#' (e.g. '#" + s + "'); "
                 + "keywords are: " + String.join(" ", new java.util.TreeSet<>(KEYWORDS)) + ".";
        }
        if (containsSymbolChar(s)) {
            return "Every token must be followed by a blank space, e.g. write '( #x )' not '(#x)'.";
        }
        return "This character sequence is not part of the SPL language.";
    }

    private static String numberHint(String s) {
        if (s.equals("-0")) {
            return "'-0' is not valid; zero is written '0'.";
        }
        if (s.matches("-?0[0-9].*")) {
            return "Numbers may not have leading zeros.";
        }
        if (s.startsWith(".") || s.startsWith("-.")) {
            return "A number needs at least one digit before the decimal point (e.g. '0.5').";
        }
        if (s.endsWith(".")) {
            return "A decimal point must be followed by at least one digit.";
        }
        if (s.indexOf('.') >= 0 && s.endsWith("0")) {
            return "The fractional part may not end in 0 (e.g. write '1.5' not '1.50', and '0' not '0.0').";
        }
        return "Malformed number.";
    }

    //parser interface

    /* peek()=current; peek(k)=k ahead (clamped at EOF); next() consumes, never past EOF; mark/reset for backtracking. */
    public static final class TokenStream {
        private final List<Token> tokens;
        private int pos = 0;

        public TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        public Token peek() {
            return peek(0);
        }

        public Token peek(int k) {
            int idx = Math.min(pos + k, tokens.size() - 1);
            return tokens.get(idx);
        }

        public Token next() {
            Token t = tokens.get(pos);
            if (t.kind != Kind.EOF) {
                pos++;
            }
            return t;
        }

        public boolean atEnd() {
            return tokens.get(pos).kind == Kind.EOF;
        }

        public int mark() {
            return pos;
        }

        public void reset(int mark) {
            pos = mark;
        }
    }

    //read file and return stream
    public static TokenStream streamFromFile(String path) throws IOException {
        return new TokenStream(tokenizeFile(path));
    }

    //stand-alone test driver: java Lexer [path] (default SPL.txt)
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "SPL.txt";
        try {
            for (Token t : tokenizeFile(path)) {
                System.out.println(t);
            }
        } catch (LexException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Cannot read '" + path + "': " + e.getMessage());
            System.exit(2);
        }
    }
}
