import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the full test suite in order:
 *   1. ProgramGen N --seed S   (generate + smoke-test N random valid programs)
 *   2. InvalidTestRunner       (the hand-made broken programs)
 *   3. TreeChecker tree.xml    (structure of the last successful tree)
 *
 * Exits non-zero if any stage fails.
 *
 * usage: java TestAll [N [--seed S] [--depth D]]   (defaults: 300, seed 1, depth 3)
 */
public final class TestAll {

    public static void main(String[] args) throws Exception {
        String[] genArgs = args.length > 0
            ? args
            : new String[]{"300", "--seed", "1", "--depth", "3"};

        int rc = 0;
        rc |= run("ProgramGen", genArgs);
        rc |= run("InvalidTestRunner", new String[0]);

        if (java.nio.file.Files.exists(java.nio.file.Paths.get("tree.xml"))) {
            rc |= run("TreeChecker", new String[]{"tree.xml"});
        } else {
            System.out.println("SKIP: tree.xml not present (last generated program failed?)");
            rc |= 1;
        }

        System.out.println(rc == 0 ? "ALL GREEN" : "SOMETHING FAILED (rc=" + rc + ")");
        System.exit(rc);
    }

    private static int run(String cls, String[] args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProgramGen.javaBin());
        cmd.add(cls);
        cmd.addAll(Arrays.asList(args));
        System.out.println("> " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start().waitFor();
    }
}