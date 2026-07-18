package cn.edu.suda.scholarsense.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ArchitectureRules {

    static final Set<String> MODULES = Set.of(
            "identityaccess", "subjectregistry", "ingestionquality", "rulegovernance",
            "signalevaluation", "cluecare", "collaboration", "reporting", "auditoperations");
    private static final Set<String> LAYERS = Set.of("api", "domain", "application", "adapters");
    private static final Set<String> SHARED_KERNELS = Set.of("id", "time", "error", "trace", "outbox");
    private static final Set<String> INFRASTRUCTURE_PACKAGES = Set.of("runtime");
    private static final String ROOT = "cn.edu.suda.scholarsense";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile(
            "\\bcn\\.edu\\.suda\\.scholarsense\\.[a-zA-Z0-9_$.]+");
    private static final Pattern DOMAIN_TECHNOLOGY_REFERENCE = Pattern.compile(
            "\\b(?:org\\.springframework|jakarta\\.persistence|javax\\.persistence|java\\.sql|javax\\.sql|"
                    + "org\\.hibernate|java\\.net\\.http|jakarta\\.servlet|javax\\.servlet|org\\.apache\\.http|"
                    + "org\\.apache\\.hc|okhttp3|retrofit2)\\.|"
                    + "\\bjava\\.net\\.(?:URL|URLConnection|HttpURLConnection)\\b");
    private static final List<String> DOMAIN_TECHNOLOGY_PREFIXES = List.of(
            "org.springframework.", "jakarta.persistence.", "javax.persistence.",
            "java.sql.", "javax.sql.", "org.hibernate.", "java.net.http.",
            "java.net.URL", "java.net.URLConnection", "java.net.HttpURLConnection",
            "jakarta.servlet.", "javax.servlet.", "org.apache.http.", "org.apache.hc.",
            "okhttp3.", "retrofit2.");
    private static final Pattern SHARED_BUSINESS_TYPE = Pattern.compile(
            "\\b(?:class|interface|record|enum)\\s+\\w*(?:Aggregate|Entity|Authorization|Permission|Policy|Business|Convenience|Repository)\\w*\\b");
    private static final Pattern JDEPS_CLASS_DEPENDENCY = Pattern.compile(
            "^\\s*(\\S+)\\s+->\\s+(\\S+)\\s+.*$");

    private ArchitectureRules() {}

    static List<String> validate(Path sourceRoot, Path classesRoot) throws IOException, InterruptedException {
        List<String> violations = new ArrayList<>();
        if (!Files.isDirectory(sourceRoot)) {
            return List.of("SOURCE_ROOT_MISSING: " + sourceRoot);
        }

        List<Path> sources;
        try (var walk = Files.walk(sourceRoot)) {
            sources = walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        Map<String, Set<String>> edges = new HashMap<>();
        for (Path source : sources) {
            inspectSource(source, Files.readString(source, StandardCharsets.UTF_8), violations, edges);
        }
        detectCycles(edges, violations);
        if (classesRoot != null && Files.isDirectory(classesRoot)) {
            inspectCompiledClasses(classesRoot, violations);
        }
        return violations.stream().distinct().sorted().toList();
    }

    private static void inspectSource(
            Path source, String content, List<String> violations, Map<String, Set<String>> edges) {
        String code = javaCodeOnly(content);
        Matcher packageMatcher = PACKAGE.matcher(code);
        if (!packageMatcher.find()) {
            violations.add("PACKAGE_MISSING: " + source);
            return;
        }
        String packageName = packageMatcher.group(1);
        if (packageName.equals(ROOT)) {
            if (!source.getFileName().toString().equals("ScholarSenseApplication.java")) {
                violations.add("ROOT_PACKAGE_TYPE_NOT_APPROVED: " + source);
                return;
            }
            inspectRootEntry(source, code, violations);
            return;
        }
        if (!packageName.startsWith(ROOT + ".")) {
            violations.add("PACKAGE_OUTSIDE_PROJECT_ROOT: " + packageName);
            return;
        }
        String[] owner = packageName.substring(ROOT.length() + 1).split("\\.");
        String module = owner[0];
        String layer = owner.length > 1 ? owner[1] : "";

        if (module.equals("shared")) {
            inspectShared(source, code, owner, violations);
            return;
        }
        if (INFRASTRUCTURE_PACKAGES.contains(module)) {
            return;
        }
        if (!MODULES.contains(module)) {
            violations.add("TOP_LEVEL_PACKAGE_NOT_APPROVED: " + packageName);
            return;
        }
        if (!LAYERS.contains(layer)) {
            violations.add("MODULE_LAYER_INVALID: " + packageName);
            return;
        }

        if (layer.equals("domain") && DOMAIN_TECHNOLOGY_REFERENCE.matcher(code).find()) {
            violations.add("DOMAIN_TECHNOLOGY_DEPENDENCY: " + source + " -> fully-qualified technology reference");
        }

        Matcher imports = IMPORT.matcher(code);
        while (imports.find()) {
            String imported = imports.group(1);
            inspectLayerDependency(source, module, layer, imported, violations, edges);
        }
        Matcher references = PROJECT_REFERENCE.matcher(code);
        while (references.find()) {
            inspectLayerDependency(source, module, layer, references.group(), violations, edges);
        }
    }

    private static void inspectRootEntry(Path source, String code, List<String> violations) {
        Matcher references = PROJECT_REFERENCE.matcher(code);
        while (references.find()) {
            ProjectImport target = projectImport(references.group());
            if (target != null
                    && !target.module.equals("runtime")
                    && !target.module.equals("shared")
                    && !target.module.startsWith("ScholarSenseApplication")) {
                violations.add("ROOT_ENTRY_DEPENDENCY_NOT_APPROVED: " + source + " -> " + references.group());
            }
        }
    }

    private static void inspectShared(Path source, String content, String[] owner, List<String> violations) {
        if (owner.length < 2 || !SHARED_KERNELS.contains(owner[1])) {
            violations.add("SHARED_KERNEL_NOT_APPROVED: " + source);
        }
        if (SHARED_BUSINESS_TYPE.matcher(content).find()) {
            violations.add("SHARED_BUSINESS_POLLUTION: " + source);
        }
        Matcher imports = IMPORT.matcher(content);
        while (imports.find()) {
            String imported = imports.group(1);
            String module = projectModule(imported);
            if (module != null && MODULES.contains(module)) {
                violations.add("SHARED_DEPENDS_ON_BUSINESS_MODULE: " + source + " -> " + imported);
            }
        }
        Matcher references = PROJECT_REFERENCE.matcher(content);
        while (references.find()) {
            String referenced = references.group();
            String module = projectModule(referenced);
            if (module != null && MODULES.contains(module)) {
                violations.add("SHARED_DEPENDS_ON_BUSINESS_MODULE: " + source + " -> " + referenced);
            }
        }
    }

    private static void inspectLayerDependency(
            Path source,
            String module,
            String layer,
            String imported,
            List<String> violations,
            Map<String, Set<String>> edges) {
        if (layer.equals("domain") && isDomainTechnology(imported)) {
            violations.add("DOMAIN_TECHNOLOGY_DEPENDENCY: " + source + " -> " + imported);
        }

        ProjectImport target = projectImport(imported);
        if (target == null || target.module.equals("shared") || !MODULES.contains(target.module)) {
            return;
        }
        if (target.module.equals(module)) {
            if (!sameModuleDependencyAllowed(layer, target.layer)) {
                violations.add("LAYER_DIRECTION_REVERSED: " + source + " -> " + imported);
            }
            return;
        }

        edges.computeIfAbsent(module, ignored -> new HashSet<>()).add(target.module);
        if (!target.layer.equals("api")) {
            violations.add("CROSS_MODULE_INTERNAL_ACCESS: " + source + " -> " + imported);
        }
    }

    private static boolean sameModuleDependencyAllowed(String sourceLayer, String targetLayer) {
        if (sourceLayer.equals(targetLayer)) {
            return true;
        }
        return switch (sourceLayer) {
            case "domain" -> false;
            case "application" -> targetLayer.equals("domain");
            case "api" -> targetLayer.equals("application");
            case "adapters" -> targetLayer.equals("application") || targetLayer.equals("domain") || targetLayer.equals("api");
            default -> false;
        };
    }

    private static boolean isDomainTechnology(String reference) {
        return DOMAIN_TECHNOLOGY_PREFIXES.stream().anyMatch(reference::startsWith);
    }

    private static String projectModule(String imported) {
        ProjectImport value = projectImport(imported);
        return value == null ? null : value.module;
    }

    private static ProjectImport projectImport(String imported) {
        if (!imported.startsWith(ROOT + ".")) {
            return null;
        }
        String[] parts = imported.substring(ROOT.length() + 1).split("\\.");
        return new ProjectImport(parts[0], parts.length > 1 ? parts[1] : "");
    }

    private static void detectCycles(Map<String, Set<String>> edges, List<String> violations) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        ArrayDeque<String> path = new ArrayDeque<>();
        for (String module : MODULES.stream().sorted().toList()) {
            visit(module, edges, visited, active, path, violations);
        }
    }

    private static void visit(
            String module,
            Map<String, Set<String>> edges,
            Set<String> visited,
            Set<String> active,
            ArrayDeque<String> path,
            List<String> violations) {
        if (active.contains(module)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(module);
            violations.add("MODULE_CYCLE: " + String.join(" -> ", cycle));
            return;
        }
        if (!visited.add(module)) {
            return;
        }
        active.add(module);
        path.addLast(module);
        for (String target : edges.getOrDefault(module, Set.of()).stream().sorted().toList()) {
            visit(target, edges, visited, active, path, violations);
        }
        path.removeLast();
        active.remove(module);
    }

    private static void inspectCompiledClasses(Path classesRoot, List<String> violations)
            throws IOException, InterruptedException {
        Path jdeps = Path.of(System.getProperty("java.home"), "bin", "jdeps");
        Process process = new ProcessBuilder(
                        jdeps.toString(),
                        "--recursive",
                        "--multi-release", "25",
                        "-verbose:class",
                        "-filter:none",
                        "--class-path", System.getProperty("java.class.path"),
                        classesRoot.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            violations.add("JDEPS_FAILED(exit=" + exit + "): " + output.strip());
        }
        Map<String, Set<String>> edges = new HashMap<>();
        for (String line : output.lines().toList()) {
            Matcher dependency = JDEPS_CLASS_DEPENDENCY.matcher(line);
            if (!dependency.matches()) {
                continue;
            }
            String sourceClass = dependency.group(1);
            String targetClass = dependency.group(2);
            if (sourceClass.equals(ROOT + ".ScholarSenseApplication")) {
                ProjectImport rootTarget = projectImport(targetClass);
                if (rootTarget != null
                        && !rootTarget.module.equals("runtime")
                        && !rootTarget.module.equals("shared")
                        && !rootTarget.module.startsWith("ScholarSenseApplication")) {
                    violations.add("COMPILED_ROOT_ENTRY_DEPENDENCY_NOT_APPROVED: "
                            + sourceClass + " -> " + targetClass);
                }
                continue;
            }
            ProjectImport source = projectImport(sourceClass);
            if (source == null) {
                continue;
            }
            if (source.module.equals("shared")) {
                ProjectImport target = projectImport(targetClass);
                if (target != null && MODULES.contains(target.module)) {
                    violations.add("COMPILED_SHARED_DEPENDS_ON_BUSINESS_MODULE: "
                            + sourceClass + " -> " + targetClass);
                }
                continue;
            }
            if (INFRASTRUCTURE_PACKAGES.contains(source.module)) {
                continue;
            }
            if (!MODULES.contains(source.module)) {
                violations.add("COMPILED_TOP_LEVEL_PACKAGE_NOT_APPROVED: " + sourceClass);
                continue;
            }
            if (!LAYERS.contains(source.layer)) {
                violations.add("COMPILED_MODULE_LAYER_INVALID: " + sourceClass);
                continue;
            }
            if (source.layer.equals("domain") && isDomainTechnology(targetClass)) {
                violations.add("COMPILED_DOMAIN_TECHNOLOGY_DEPENDENCY: "
                        + sourceClass + " -> " + targetClass);
            }
            ProjectImport target = projectImport(targetClass);
            if (target == null || target.module.equals("shared") || !MODULES.contains(target.module)) {
                continue;
            }
            if (source.module.equals(target.module)) {
                if (!sameModuleDependencyAllowed(source.layer, target.layer)) {
                    violations.add("COMPILED_LAYER_DIRECTION_REVERSED: "
                            + sourceClass + " -> " + targetClass);
                }
                continue;
            }
            edges.computeIfAbsent(source.module, ignored -> new HashSet<>()).add(target.module);
            if (!target.layer.equals("api")) {
                violations.add("COMPILED_CROSS_MODULE_INTERNAL_ACCESS: "
                        + sourceClass + " -> " + targetClass);
            }
        }
        List<String> compiledCycles = new ArrayList<>();
        detectCycles(edges, compiledCycles);
        compiledCycles.stream()
                .filter(value -> value.startsWith("MODULE_CYCLE:"))
                .map(value -> "COMPILED_" + value)
                .forEach(violations::add);
    }

    private static String javaCodeOnly(String content) {
        StringBuilder result = new StringBuilder(content.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length() ? content.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    result.append('\n');
                } else {
                    result.append(' ');
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    index++;
                    blockComment = false;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
            } else if (string || character) {
                result.append(current == '\n' ? '\n' : ' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if ((string && current == '"') || (character && current == '\'')) {
                    string = false;
                    character = false;
                }
            } else if (current == '/' && next == '/') {
                result.append("  ");
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result.append("  ");
                index++;
                blockComment = true;
            } else if (current == '"') {
                result.append(' ');
                string = true;
            } else if (current == '\'') {
                result.append(' ');
                character = true;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private record ProjectImport(String module, String layer) {}
}
