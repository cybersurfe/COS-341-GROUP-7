package codegen;

import java.util.*;
import static codegen.CodeGen.*;

public class CodeGenDemo {
    static final int X = 1, Y = 2, I = 3;
    static Map<Integer, String> NAMES = new HashMap<>();
    static { NAMES.put(X, "sys1"); NAMES.put(Y, "sys2"); NAMES.put(I, "sys3"); }

    public static void main(String[] args) {
        banner("1) print \"Hello\"");
        Algo a1 = new Algo();
        a1.instrs.add(new PrintString("Hello"));
        run(a1);

        banner("2) #x = 5");
        Algo a2 = new Algo();
        a2.instrs.add(new Assign(X, new Num("5")));
        run(a2);

        banner("3) #x = add(1, mul(2,3))");
        Algo a3 = new Algo();
        a3.instrs.add(new Assign(X,
            new BinOp("add", new Num("1"),
                new BinOp("mul", new Num("2"), new Num("3")))));
        run(a3);

        banner("4) if eq(#x,#y) then {print #x} else {print #y}");
        Algo thenA = new Algo(); thenA.instrs.add(new PrintTerm(new Name(X)));
        Algo elseA = new Algo(); elseA.instrs.add(new PrintTerm(new Name(Y)));
        Algo a4 = new Algo();
        a4.instrs.add(new Branch(new Cmp("eq", new Name(X), new Name(Y)), thenA, elseA));
        run(a4);

        banner("5) while lesser(#i,10) do { #i = add(#i,1) }");
        Algo bodyA = new Algo();
        bodyA.instrs.add(new Assign(I, new BinOp("add", new Name(I), new Num("1"))));
        Algo a5 = new Algo();
        a5.instrs.add(new Loop(LoopKind.WHILE_DO,
            new Cmp("lesser", new Name(I), new Num("10")), bodyA));
        run(a5);
    }

    static void banner(String s) {
        System.out.println("\n════════════════════════════════════════════════════════════");
        System.out.println("   " + s);
        System.out.println("════════════════════════════════════════════════════════════");
    }

    static void run(Algo a) {
        CodeGen cg = new CodeGen("int");
        cg.setNames(NAMES);
        System.out.print(cg.generate(a));
    }
}
