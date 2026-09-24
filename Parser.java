import java.io.IOException; //throws if the input file cant be read or tree.xml cant be written
import java.io.PrintWriter; //used to dump the finished xml string to disk
import java.nio.charset.StandardCharsets; //write tree.xml as UTF-8, same as the lexer reads it
import java.nio.file.Files; //static helpers for opening files
import java.nio.file.Paths; //turns a path string into a Path object
import java.util.ArrayList;
import java.util.List;

/*
NOTE on imports: Lexer.java has no package line, so it lives in the "unnamed package". java does NOT let you import anything from the unnamed package (import Lexer.Token; gives
"package Lexer does not exist"), so instead of importing i just write Lexer.Token, Lexer.Kind, Lexer.TokenStream and Lexer.LexException in full everywhere below.
both files sit in the same folder so javac finds Lexer without any import. (if the group ever moves everything into a real package this can be tidied up)
*/

/*
the parsers job is to take the token list the lexer made and check that it follows the SPL
grammar. if it does, we build the syntax tree and write it out as tree.xml. if it doesnt,
we throw a syntax error with line/column and a hint, like the lexer does for bad tokens.

architecture decision (spec asks us to decide lexer-less vs separate lexer):
went with a separate lexer plugged in front of the parser. reasons:
  - the spec says every token ends with a blank, so the lexer is dead simple (split on blanks)
    and the parser never has to think about characters, only tokens
  - the parser can peek at tokens instead of chars, so 1 or 2 tokens of lookahead is cheap
  - lexical errors (bad number, bad name) and syntax errors (wrong order of tokens) come
    from 2 different places so the error messages are easier to make helpful

which parsing method: recursive descent (textbook Mogensen "Introduction to Compiler Design",
chapter 2, section 2.11.1, Fig 2.17 and Fig 2.18). idea from the book: every nonterminal
becomes one function, the function looks at the next token to pick the production, then walks
the right hand side: terminals get matched against the input, nonterminals get a function call.
i picked this over the table driven version (section 2.11.2) because:
  - the code reads almost 1:1 like the grammar in the spec, so its easy to check against it
  - error messages can be custom per nonterminal (a table just says "error")
  - we dont need to build the LL(1) table by hand
so Token.terminals() in the Lexer (the column names for a table) is not needed here. its
there if anyone wants to do the table version later.
*/

/*
is the SPL grammar LL(1)? (spec to-do asks us to analyse this, so heres the working)

nullable nonterminals (can derive epsilon): V_DECL, F_DECL, ALGO, INPUT. thats all of them.
book section 2.8 says a nonterminal can have at most one nullable production. each of these
has exactly one epsilon production so thats fine.

FIRST sets (what a nonterminal can start with, book section 2.7):
  V_DECL  = { NAME }                       F_DECL = F_TYPE = { void, num }
  ALGO    = INSTR = { NAME, print, nop, comment, if, while, until, do }
  TERM    = INPUT = { NAME, NUM, mod, add, sub, mul, div, neg }
  BOOL    = { not, and, or, eq, larger, lesser }
  LOOP    = { while, until, do }           COND = { while, until }
  OUTP    = { (, STRING }                  BRANCH = { if }
  CALL    = ASSIGN = { NAME }

FOLLOW sets for the nullable ones (what can come right after them, book section 2.9):
  V_DECL = { :, ) }        F_DECL = { : }
  ALGO   = { $, return, } }    INPUT = { ) }

the book rule (section 2.8): pick production N -> alpha on token c if c is in FIRST(alpha), or
alpha is nullable and c is in FOLLOW(N). for that to be deterministic the sets must not overlap:
  - V_DECL: FIRST { NAME } vs FOLLOW { :, ) }         => no overlap, ok
  - F_DECL: FIRST { void, num } vs FOLLOW { : }       => no overlap, ok
  - ALGO:   FIRST { NAME, print, ... } vs FOLLOW { $, return, } } => no overlap, ok
  - INPUT:  FIRST { NAME, NUM, mod, ... } vs FOLLOW { ) } => no overlap, ok
every other nonterminal has alternatives that start with different tokens (INSTR splits on
print / nop / comment / if / loop keywords, TERM on the operator keywords, BOOL on its keywords,
OUTP on ( vs STRING, F_TYPE on void vs num, LOOP on while/until vs do).

BUT there are 2 places where 2 alternatives start with the same token (NAME), which is a
FIRST/FIRST conflict, so strictly speaking the grammar is not LL(1) there (book section 2.11.3):
  1. INSTR -> ASSIGN | CALL        ASSIGN = NAME = TERM,  CALL = NAME ( INPUT )
  2. TERM  -> NAME | CALL          CALL = NAME ( INPUT )
the book fix would be left-factorisation (section 2.12.2, like their ElsePart example): pull the
NAME out and add a helper nonterminal for the rest. i did NOT do that because the spec wants
tree.xml to have real ASSIGN and CALL nodes exactly as in the grammar, and later phases (2a
scopes, 2b types) key off those nodes. factoring would change the tree shape.
instead i look ONE token further ahead with peek(1): after a NAME, "=" means ASSIGN and "("
means CALL (in TERM, anything else means the bare NAME). the second token is always enough to
decide, so its LL(2) at exactly those 2 spots and LL(1) everywhere else.
no conflict between the 2 spots and anything else, because after a bare TERM -> NAME the next
token can never be "(" (nothing in the grammar allows "(" right after a TERM).

also no left recursion anywhere in the grammar (every recursive rule has a terminal or a
different nonterminal in front), so no rewriting was needed there (book section 2.12.1).
and the book (section 2.12.3 step 4) says to add S' -> S$ as an extra start rule. the spec
already gives that as SPL_PROG -> P $, so parseProgram() below is that rule.
*/

/*
output format from the spec, tree.xml:
  root:  id, contents (start symbol), children
  inner: id, contents (non-terminal), children, parent
  leaf:  id, contents (the terminal token), parent
ids must be unique across the whole tree, later phases use them as foreign keys for the
symbol table (spec hint).
*/
public final class Parser {

    //syntax tree node

    /*
    one class for all 3 kinds of node from the spec (root, inner, leaf).
    the difference is just which fields are used:
      root  => parent == null
      inner => isLeaf false, has children and a parent
      leaf  => isLeaf true, no children, has a parent
    so writeXml() can tell them apart without a separate kind field.
    */
    static final class Node {
        final int id;
        final String contents;       //non-terminal name for inner nodes, the token text for leaves
        final boolean isLeaf;
        final int line, col;         //only meaningful for leaves. inner nodes get -1. kept for later phases error messages
        final List<Node> children = new ArrayList<>();
        Node parent;                 //not final because we only know the parent AFTER the child was built (see attach)

        Node(int id, String contents, boolean isLeaf, int line, int col) {
            this.id = id;
            this.contents = contents;
            this.isLeaf = isLeaf;
            this.line = line;
            this.col = col;
        }
    }

    //parse error

    /*
    thrown when the tokens are all valid but in the wrong order.
    kept separate from Lexer.LexException on purpose so main() (and later the whole front-end driver)
    can tell "bad token" apart from "bad structure". RuntimeException so we dont need throws
    clauses on every parse function. the line/column is put in the message text by whoever throws it.
    */
    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }

    //parser state

    private final Lexer.TokenStream in;  //the lexer's stream, gives us peek(), peek(k) and next()
    private int nextId = 1;              //id counter, starts at 1. only ever goes up so ids can never repeat

    public Parser(Lexer.TokenStream in) {
        this.in = in;
    }

    //hands out the next unused id. this is the only place ids are made, which is what guarantees uniqueness
    private int freshId() { return nextId++; }

    //makes a non-terminal node. line/col are -1 since a non-terminal is not one token
    private Node newInner(String contents) {
        return new Node(freshId(), contents, false, -1, -1);
    }

    /*
    links child under parent both ways (parent pointer and children list).
    the book (Fig 2.18) builds the tree bottom up: each parse function returns its subtree and the
    caller combines them. same here, every parseX() returns a finished Node and the caller attaches it.
    child.parent is set here, so no parse function has to remember to do it.
    */
    private void attach(Node parent, Node child) {
        child.parent = parent;
        parent.children.add(child);
    }

    //shortcuts so the parse functions dont keep writing in.peek()
    private Lexer.Token peek() { return in.peek(); }
    private Lexer.Token peek(int k) { return in.peek(k); } //lexer clamps this at EOF so peek(1) is always safe

    //"line 3, column 7" of the current token, for error messages (same wording as the LexException)
    private String here() {
        Lexer.Token t = peek();
        return "line " + t.line + ", column " + t.col;
    }

    //eat the current token and wrap it as a leaf node. this is tNode(...) from the book's Fig 2.18
    private Node eatAsLeaf() {
        Lexer.Token t = in.next();
        //exactText already has en dash turned into '-' by the lexer, so tree.xml only ever has plain '-'
        return new Node(freshId(), t.exactText, true, t.line, t.col);
    }

    /*
    this is match(...) from the book (Fig 2.17): the next token must be exactly this keyword or
    symbol, otherwise error. used for fixed terminals like "(" ":" "void" "return".
    the kind check is really just a safety net: names start with # and strings start with "
    so their text could never equal a keyword or symbol anyway, but this makes the intent obvious
    (expect() is only for keywords and symbols, names/strings/nums go through expectKind).
    the message says what was expected AND what we got with its kind, so the user can see for
    example that they wrote a name where a symbol should be.
    */
    private Node expect(String expectedText) {
        Lexer.Token t = peek();
        if (t.exactText.equals(expectedText) && t.kind != Lexer.Kind.NAME && t.kind != Lexer.Kind.STRING) {
            return eatAsLeaf();
        }
        throw new ParseException("Syntax error at " + here() + ": expected '" + expectedText
                + "' but found '" + t.exactText + "' (" + t.kind + ").");
    }

    /*
    same idea but for the token CATEGORIES (NAME, NUM, STRING) where the exact text can be anything.
    "what" is a human readable description for the error message, e.g. "a function name".
    */
    private Node expectKind(Lexer.Kind kind, String what) {
        Lexer.Token t = peek();
        if (t.kind == kind) {
            return eatAsLeaf();
        }
        throw new ParseException("Syntax error at " + here() + ": expected " + what
                + " but found '" + t.exactText + "' (" + t.kind + ").");
    }

    //lookahead helpers. they only LOOK, they never consume. this is what makes the parser predictive (book section 2.6)
    private boolean atSymbol(String s) {
        Lexer.Token t = peek();
        return t.kind == Lexer.Kind.SYMBOL && t.exactText.equals(s);
    }

    private boolean atKeyword(String s) {
        Lexer.Token t = peek();
        return t.kind == Lexer.Kind.KEYWORD && t.exactText.equals(s);
    }

    private boolean atName() { return peek().kind == Lexer.Kind.NAME; }

    //entry point

    /*
    SPL_PROG -> P $
    this is the extra start rule the book adds (section 2.12.3 step 4, and parseT' in Fig 2.17).
    after P is parsed the next token has to be EOF, if there is anything left over then the
    program had extra tokens at the end, e.g. an extra "}" or a second ALGO.
    */
    /** Parses SPL_PROG -> P $  and returns the root node. */
    public Node parseProgram() {
        Node root = newInner("SPL_PROG");
        Node p = parseP();
        attach(root, p);
        if (peek().kind != Lexer.Kind.EOF) {
            throw new ParseException("Syntax error at " + here()
                    + ": expected end of input ('$') but found trailing token '"
                    + peek().exactText + "'.");
        }
        //the $ is NOT added as a leaf. Announcement #23 says $ is only a meta symbol for reasoning
        //about the parser and is not a token of the SPL language, so it doesnt belong in tree.xml.
        //we still check for it above because thats what makes trailing garbage an error.
        return root;
    }

    //P -> V_DECL : F_DECL : ALGO
    //no decision to make, only one production. this is the "trivial choice" case from the book.
    private Node parseP() {
        Node n = newInner("P");
        attach(n, parseVDecl());
        attach(n, expect(":"));
        attach(n, parseFDecl());
        attach(n, expect(":"));
        attach(n, parseAlgo());
        return n;
    }

    //V_DECL -> epsilon | NAME V_DECL
    private Node parseVDecl() {
        Node n = newInner("V_DECL");
        //FIRST(NAME V_DECL) = { NAME }, so a name means take the 2nd production.
        //otherwise take epsilon. the book (section 2.8) says epsilon is only valid on FOLLOW(V_DECL)
        //= { :, ) }. i dont check that here, i just return and let the caller's expect(":") or
        //expect(")") complain if the token is wrong. same set of programs is accepted, the error
        //is just reported one step later (and the message still points at the exact bad token).
        if (atName()) {
            attach(n, expectKind(Lexer.Kind.NAME, "a user-defined name"));
            attach(n, parseVDecl());
        }
        //else epsilon: an inner node with no children. this matches tNode('R', []) in the book's Fig 2.18.
        //tree.xml still gets the V_DECL node so later phases (2a, 2b) can find "this V_DECL is empty".
        return n;
    }

    //F_DECL -> epsilon | F_TYPE F_DECL
    private Node parseFDecl() {
        Node n = newInner("F_DECL");
        //FIRST(F_TYPE) = { void, num }, anything else is epsilon (FOLLOW(F_DECL) = { : })
        if (atKeyword("void") || atKeyword("num")) {
            attach(n, parseFType());
            attach(n, parseFDecl());
        }
        return n;
    }

    /*
    F_TYPE -> void NAME ( V_DECL ) { P return }
            | num  NAME ( V_DECL ) { P return ( TERM ) }
    the 2 alternatives start with different keywords so 1 token of lookahead is enough.
    kept as 2 branches instead of sharing code because the endings differ (void has no return value).
    the function body is a full P, which is why scopes nest: every function has its own V_DECL,
    F_DECL and ALGO (this is Fig.1 in the 2a spec). the parser doesnt care about scope levels,
    that is for the 2a phase, it just builds the nested P nodes.
    */
    private Node parseFType() {
        Node n = newInner("F_TYPE");
        if (atKeyword("void")) {
            attach(n, expect("void"));
            attach(n, expectKind(Lexer.Kind.NAME, "a function name"));
            attach(n, expect("("));
            attach(n, parseVDecl());
            attach(n, expect(")"));
            attach(n, expect("{"));
            attach(n, parseP());
            attach(n, expect("return"));
            attach(n, expect("}"));
        } else if (atKeyword("num")) {
            attach(n, expect("num"));
            attach(n, expectKind(Lexer.Kind.NAME, "a function name"));
            attach(n, expect("("));
            attach(n, parseVDecl());
            attach(n, expect(")"));
            attach(n, expect("{"));
            attach(n, parseP());
            attach(n, expect("return"));
            attach(n, expect("("));
            attach(n, parseTerm());
            attach(n, expect(")"));
            attach(n, expect("}"));
        } else {
            //only reachable if parseFType() is called by mistake, parseFDecl already checks first.
            //left in as a safety net so a future change cant silently build a broken node
            throw new ParseException("Syntax error at " + here()
                    + ": expected 'void' or 'num' to start a function declaration.");
        }
        return n;
    }

    //ALGO -> epsilon | INSTR ; ALGO
    private Node parseAlgo() {
        Node n = newInner("ALGO");
        //every instruction ends with ";" in this grammar (its a terminator, not a separator),
        //so an ALGO is a list of "INSTR ;" and it stops as soon as the next token cant start an INSTR.
        //what stops it: FOLLOW(ALGO) = { $, return, } }. these never overlap with FIRST(INSTR),
        //which is why the epsilon choice is safe.
        if (startsInstr()) {
            attach(n, parseInstr());
            attach(n, expect(";"));
            attach(n, parseAlgo());
        }
        return n;
    }

    //true if the current token is in FIRST(INSTR). used to decide between the 2 ALGO productions
    private boolean startsInstr() {
        Lexer.Token t = peek();
        if (t.kind == Lexer.Kind.NAME) return true; //assignment or call both start with a name
        if (t.kind != Lexer.Kind.KEYWORD) return false;
        switch (t.exactText) {
            case "print": case "nop": case "comment":
            case "if": case "while": case "until": case "do":
                return true;
            default:
                return false; //includes "return", which is what ends a function body's ALGO
        }
    }

    //INSTR -> print OUTP | nop | comment STRING | ASSIGN | BRANCH | LOOP | CALL
    private Node parseInstr() {
        Node n = newInner("INSTR");
        if (atKeyword("print")) {
            attach(n, expect("print"));
            attach(n, parseOutp());
        } else if (atKeyword("nop")) {
            attach(n, expect("nop")); //no-operation, mostly for empty else cases (grammar comment)
        } else if (atKeyword("comment")) {
            attach(n, expect("comment"));
            attach(n, expectKind(Lexer.Kind.STRING, "a string literal"));
        } else if (atKeyword("if")) {
            attach(n, parseBranch());
        } else if (atKeyword("while") || atKeyword("until") || atKeyword("do")) {
            attach(n, parseLoop());
        } else if (atName()) {
            //the FIRST/FIRST conflict from the big comment at the top. both ASSIGN and CALL
            //start with a NAME so 1 token isnt enough, look at the 2nd one:
            //   NAME =  => ASSIGN
            //   NAME (  => CALL
            //(a call used as an instruction has to be a void function, but checking that is the
            //type analysis job in 2b, the parser only cares about the shape)
            if (peek(1).kind == Lexer.Kind.SYMBOL && peek(1).exactText.equals("=")) {
                attach(n, parseAssign());
            } else if (peek(1).kind == Lexer.Kind.SYMBOL && peek(1).exactText.equals("(")) {
                attach(n, parseCall());
            } else {
                throw new ParseException("Syntax error at " + here()
                        + ": after a name here, expected '=' (assignment) or '(' (function call).");
            }
        } else {
            throw new ParseException("Syntax error at " + here() + ": expected an instruction "
                    + "(print/nop/comment/if/while/until/do/an assignment/a function call), "
                    + "but found '" + peek().exactText + "'.");
        }
        return n;
    }

    //OUTP -> ( TERM ) | STRING
    private Node parseOutp() {
        Node n = newInner("OUTP");
        //"(" vs a string token, different starts so 1 token decides
        if (atSymbol("(")) {
            attach(n, expect("("));
            attach(n, parseTerm());
            attach(n, expect(")"));
        } else if (peek().kind == Lexer.Kind.STRING) {
            attach(n, expectKind(Lexer.Kind.STRING, "a string literal"));
        } else {
            throw new ParseException("Syntax error at " + here()
                    + ": expected '(' or a string literal after 'print'.");
        }
        return n;
    }

    //CALL -> NAME ( INPUT )
    //only ever entered after the caller already saw NAME followed by "(" (see parseInstr / parseTerm)
    private Node parseCall() {
        Node n = newInner("CALL");
        attach(n, expectKind(Lexer.Kind.NAME, "a function name"));
        attach(n, expect("("));
        attach(n, parseInput());
        attach(n, expect(")"));
        return n;
    }

    //INPUT -> epsilon | TERM INPUT
    //a call's arguments are just terms separated by blanks (no commas in SPL), so it is a list
    //that stops at ")" which is FOLLOW(INPUT). same list pattern as V_DECL and ALGO.
    private Node parseInput() {
        Node n = newInner("INPUT");
        if (startsTerm()) {
            attach(n, parseTerm());
            attach(n, parseInput());
        }
        return n;
    }

    //true if the current token is in FIRST(TERM). used by parseInput to know if there is another argument
    private boolean startsTerm() {
        Lexer.Token t = peek();
        if (t.kind == Lexer.Kind.NAME || t.kind == Lexer.Kind.NUM) return true;
        if (t.kind != Lexer.Kind.KEYWORD) return false;
        switch (t.exactText) {
            case "mod": case "add": case "sub": case "mul": case "div": case "neg":
                return true;
            default:
                return false;
        }
    }

    //ASSIGN -> NAME = TERM
    private Node parseAssign() {
        Node n = newInner("ASSIGN");
        attach(n, expectKind(Lexer.Kind.NAME, "a variable name"));
        attach(n, expect("="));
        attach(n, parseTerm());
        return n;
    }

    /*
    TERM -> NAME | NUM | CALL | mod(T T) | add(T T) | sub(T T) | mul(T T) | div(T T) | neg(T)
    the 2nd of the 2 lookahead spots. TERM -> NAME and TERM -> CALL both start with a NAME,
    so peek(1): "(" means a function call, anything else means a plain variable.
    this is safe because a bare name in a term is never followed by "(" in a valid program.
    everything else has its own starting keyword or is a NUM.

    mod/add/sub/mul/div share one branch because they have the same shape: op ( TERM TERM ).
    neg is separate since it only takes 1 term. the operator keyword is stored as the first leaf
    so later phases can tell which operator it is just from the leaf contents.
    NOTE: the "mod and div in the same program" and "no decimal point when mod is used" rules from
    2b are NOT checked here, those are type analysis rules, the parser accepts these programs.
    */
    private Node parseTerm() {
        Node n = newInner("TERM");
        Lexer.Token t = peek();
        if (t.kind == Lexer.Kind.NAME) {
            if (peek(1).kind == Lexer.Kind.SYMBOL && peek(1).exactText.equals("(")) {
                attach(n, parseCall());
            } else {
                attach(n, expectKind(Lexer.Kind.NAME, "a variable name"));
            }
        } else if (t.kind == Lexer.Kind.NUM) {
            attach(n, expectKind(Lexer.Kind.NUM, "a number"));
        } else if (atKeyword("mod") || atKeyword("add") || atKeyword("sub")
                || atKeyword("mul") || atKeyword("div")) {
            String op = t.exactText; //remember which of the 5 it is so expect() eats the right one
            attach(n, expect(op));
            attach(n, expect("("));
            attach(n, parseTerm());
            attach(n, parseTerm());
            attach(n, expect(")"));
        } else if (atKeyword("neg")) {
            attach(n, expect("neg"));
            attach(n, expect("("));
            attach(n, parseTerm());
            attach(n, expect(")"));
        } else {
            throw new ParseException("Syntax error at " + here()
                    + ": expected a term (a name, a number, a function call, or "
                    + "mod/add/sub/mul/div/neg), but found '" + t.exactText + "'.");
        }
        return n;
    }

    //BRANCH -> if BOOL then { ALGO } else { ALGO }
    //the else part is NOT optional in SPL (thats what nop is for, see the grammar comment), so
    //there is no dangling else problem. the book (section 2.12.2) has to use priorities to fix
    //if-then-else in its own example grammar, we dont need that here, one production and done.
    private Node parseBranch() {
        Node n = newInner("BRANCH");
        attach(n, expect("if"));
        attach(n, parseBool());
        attach(n, expect("then"));
        attach(n, expect("{"));
        attach(n, parseAlgo());
        attach(n, expect("}"));
        attach(n, expect("else"));
        attach(n, expect("{"));
        attach(n, parseAlgo());
        attach(n, expect("}"));
        return n;
    }

    /*
    BOOL -> not(BOOL) | and(BOOL BOOL) | or(BOOL BOOL)
          | eq(T T) | larger(T T) | lesser(T T)
    every alternative starts with its own keyword. and/or share a branch (same shape, 2 BOOLs),
    eq/larger/lesser share a branch (same shape, 2 TERMs). the difference matters: comparisons
    take TERMs (numbers) and the logic operators take BOOLs. mixing them up is a syntax error
    right here because we call parseTerm() or parseBool() accordingly.
    */
    private Node parseBool() {
        Node n = newInner("BOOL");
        if (atKeyword("not")) {
            attach(n, expect("not"));
            attach(n, expect("("));
            attach(n, parseBool());
            attach(n, expect(")"));
        } else if (atKeyword("and") || atKeyword("or")) {
            String op = peek().exactText;
            attach(n, expect(op));
            attach(n, expect("("));
            attach(n, parseBool());
            attach(n, parseBool());
            attach(n, expect(")"));
        } else if (atKeyword("eq") || atKeyword("larger") || atKeyword("lesser")) {
            String op = peek().exactText;
            attach(n, expect(op));
            attach(n, expect("("));
            attach(n, parseTerm());
            attach(n, parseTerm());
            attach(n, expect(")"));
        } else {
            throw new ParseException("Syntax error at " + here()
                    + ": expected a boolean expression (not/and/or/eq/larger/lesser), "
                    + "but found '" + peek().exactText + "'.");
        }
        return n;
    }

    /*
    LOOP -> COND BOOL do { ALGO } | do { ALGO } COND BOOL
    while/until loops test first, do-loops test after. the 2 productions start with different
    tokens (while/until vs do) so 1 token decides. COND is its own node (not just a leaf) because
    the grammar has COND as a nonterminal, and code gen will need to know while vs until.
    note the do-loop form does NOT end with "do", it ends with the COND BOOL and then the
    ";" comes from the ALGO that contains this loop.
    */
    private Node parseLoop() {
        Node n = newInner("LOOP");
        if (atKeyword("while") || atKeyword("until")) {
            attach(n, parseCond());
            attach(n, parseBool());
            attach(n, expect("do"));
            attach(n, expect("{"));
            attach(n, parseAlgo());
            attach(n, expect("}"));
        } else if (atKeyword("do")) {
            attach(n, expect("do"));
            attach(n, expect("{"));
            attach(n, parseAlgo());
            attach(n, expect("}"));
            attach(n, parseCond());
            attach(n, parseBool());
        } else {
            throw new ParseException("Syntax error at " + here()
                    + ": expected 'while', 'until' or 'do' to start a loop.");
        }
        return n;
    }

    //COND -> while | until
    private Node parseCond() {
        Node n = newInner("COND");
        if (atKeyword("while")) {
            attach(n, expect("while"));
        } else if (atKeyword("until")) {
            attach(n, expect("until"));
        } else {
            throw new ParseException("Syntax error at " + here()
                    + ": expected 'while' or 'until'.");
        }
        return n;
    }

    //xml output

    /*
    writes the tree to tree.xml the way the spec describes. one <node> per tree node, with
    child elements for the fields: <contents>, <children> (comma separated ids), <parent>.
    i went with elements instead of attributes since the spec words it as "fields" and a browser
    renders both fine. only id is an attribute so every node is easy to spot.
    the 3 cases match the spec:
      leaf  => id, contents, parent
      root  => id, contents, children          (no parent because it has none)
      inner => id, contents, children, parent
    nodes are written in pre-order (parent before its children), and since ids are handed out in
    the order the parser creates nodes, the ids also come out in increasing order in the file.
    built in a StringBuilder first and written once at the end, so if something throws half way
    we dont leave a half written tree.xml behind.
    */
    /** Writes the tree to tree.xml in the format required by the spec. */
    public void writeXml(Node root, String path) throws IOException {
        List<Node> all = new ArrayList<>();
        collect(root, all);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<tree>\n");
        for (Node node : all) {
            if (node.isLeaf) {
                sb.append("  <node id=\"").append(node.id).append("\">\n");
                sb.append("    <contents>").append(escape(node.contents)).append("</contents>\n");
                sb.append("    <parent>").append(node.parent.id).append("</parent>\n");
                sb.append("  </node>\n");
            } else if (node.parent == null) {
                //root
                sb.append("  <node id=\"").append(node.id).append("\">\n");
                sb.append("    <contents>").append(escape(node.contents)).append("</contents>\n");
                sb.append("    <children>").append(childList(node)).append("</children>\n");
                sb.append("  </node>\n");
            } else {
                sb.append("  <node id=\"").append(node.id).append("\">\n");
                sb.append("    <contents>").append(escape(node.contents)).append("</contents>\n");
                sb.append("    <children>").append(childList(node)).append("</children>\n");
                sb.append("    <parent>").append(node.parent.id).append("</parent>\n");
                sb.append("  </node>\n");
            }
        }
        sb.append("</tree>\n");

        //try-with-resources so the file is closed even if print throws
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            w.print(sb);
        }
    }

    //ids of the children as "3,4,5". an epsilon node has no children so this gives "" (empty <children>)
    private String childList(Node n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.children.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(n.children.get(i).id);
        }
        return sb.toString();
    }

    //flattens the tree into a list, parent first then each child subtree in order (pre-order walk)
    private void collect(Node n, List<Node> out) {
        out.add(n);
        for (Node c : n.children) collect(c, out);
    }

    //xml needs these 4 chars escaped. real strings from the lexer cant contain < or & (the STRING
    //regex is tiny) but the token text goes straight into the file so escaping is cheap insurance
    //against ever producing a broken tree.xml. "&" MUST be replaced first or we would escape our
    //own escapes (&lt; would turn into &amp;lt;)
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                 .replace("\"", "&quot;");
    }

    //stand-alone driver: java Parser [path] (default SPL.txt)

    /*
    same shape as Lexer.main so they behave alike. exit codes:
      0 = parsed fine, tree.xml written
      1 = the program itself is wrong (lexical OR syntax error), message goes to stderr
      2 = the file couldnt be read or written (io problem, not the users program)
    the tutors black-box test the executable (Announcement #23) so a clean message on stderr and a
    non-zero exit code on bad input is what they will actually see.
    */
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "SPL.txt";
        try {
            Lexer.TokenStream ts = Lexer.streamFromFile(path);
            Parser parser = new Parser(ts);
            Node root = parser.parseProgram();
            parser.writeXml(root, "tree.xml");
            System.out.println("OK: parsed '" + path + "' successfully. Wrote tree.xml.");
        } catch (Lexer.LexException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Cannot read '" + path + "': " + e.getMessage());
            System.exit(2);
        }
    }
}