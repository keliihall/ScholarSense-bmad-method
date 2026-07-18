package cn.edu.suda.scholarsense.architecture;

import java.nio.file.Path;

public final class ArchitectureGuardMain {

    private ArchitectureGuardMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: ArchitectureGuardMain <source-root> [classes-root]");
            System.exit(2);
        }
        Path classes = args.length == 2 ? Path.of(args[1]) : null;
        var violations = ArchitectureRules.validate(Path.of(args[0]), classes);
        violations.forEach(System.err::println);
        if (!violations.isEmpty()) {
            System.exit(1);
        }
        System.out.println("architecture-guard: PASS");
    }
}
