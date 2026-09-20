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

}
