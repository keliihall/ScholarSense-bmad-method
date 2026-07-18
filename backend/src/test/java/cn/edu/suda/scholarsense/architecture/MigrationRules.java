package cn.edu.suda.scholarsense.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MigrationRules {

    private static final String IDENTIFIER = "(?:\"[^\"]+\"|[a-z][a-z0-9_]*)";
    private static final Pattern MIGRATION_NAME = Pattern.compile(
            "^V([0-9]{6})__([a-z][a-z0-9-]*)__([a-z][a-z0-9_]*)\\.sql$");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
            "(?i)(" + IDENTIFIER + ")\\s*\\.\\s*(" + IDENTIFIER + ")");
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:table|into|update|from|join|references)\\s+(?:(" + IDENTIFIER
                    + ")\\s*\\.\\s*)?(" + IDENTIFIER + ")");
    private static final Pattern FOREIGN_REFERENCE = Pattern.compile(
            "(?i)\\breferences\\s+(?:(" + IDENTIFIER + ")\\s*\\.\\s*)?(" + IDENTIFIER + ")");
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+(?:(?:" + IDENTIFIER + ")\\s*\\.\\s*)?"
                    + IDENTIFIER + "\\s+(?:as\\s+)?(" + IDENTIFIER + ")");
    private static final Pattern ADDITIONAL_TABLE_REFERENCE = Pattern.compile(
            "(?i)(?:\\bcreate\\s+(?:unique\\s+)?index\\s+(?:concurrently\\s+)?"
                    + "(?:if\\s+not\\s+exists\\s+)?(?:" + IDENTIFIER + "\\s+)?on|"
                    + "\\btruncate(?:\\s+table)?|\\bcomment\\s+on\\s+table|\\breindex\\s+table)"
                    + "\\s+(?:(" + IDENTIFIER + ")\\s*\\.\\s*)?(" + IDENTIFIER + ")");
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "join", "left", "right", "full", "inner", "outer", "cross", "on",
            "group", "order", "limit", "offset", "returning", "set", "values", "union");

    private MigrationRules() {}

    static Result validate(Path registry, Path migrationRoot) throws IOException {
        List<String> violations = new ArrayList<>();
        Map<String, Ownership> ownership = readRegistry(registry, violations);
        if (!Files.isDirectory(migrationRoot)) {
            violations.add("MIGRATION_ROOT_MISSING: " + migrationRoot);
            return new Result(ownership, violations.stream().distinct().sorted().toList());
        }
        for (String module : ownership.keySet()) {
            if (!Files.isDirectory(migrationRoot.resolve(module))) {
                violations.add("OWNER_DIRECTORY_MISSING: " + module);
            }
        }

        Map<String, Path> versions = new HashMap<>();
        try (var walk = Files.walk(migrationRoot)) {
            for (Path sql : walk.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                inspectMigration(sql, migrationRoot, ownership, versions, violations);
            }
        }
        return new Result(ownership, violations.stream().distinct().sorted().toList());
    }

    private static Map<String, Ownership> readRegistry(Path registry, List<String> violations) throws IOException {
        Map<String, Ownership> result = new HashMap<>();
        if (!Files.isRegularFile(registry)) {
            violations.add("OWNERSHIP_REGISTRY_MISSING: " + registry);
            return result;
        }
        List<String> lines = Files.readAllLines(registry, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("module,schema,tablePrefix,factOwners")) {
            violations.add("OWNERSHIP_REGISTRY_HEADER_INVALID");
            return result;
        }
        Set<String> schemas = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        Map<String, String> factOwners = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(",", -1);
            if (fields.length != 4 || fields[3].isBlank()) {
                violations.add("OWNERSHIP_REGISTRY_ROW_INVALID: line " + (index + 1));
                continue;
            }
            Set<String> facts = new HashSet<>();
            for (String fact : fields[3].split("\\|", -1)) {
                if (fact.isBlank()) {
                    violations.add("OWNERSHIP_REGISTRY_ROW_INVALID: line " + (index + 1));
                    continue;
                }
                String previousOwner = factOwners.putIfAbsent(fact, fields[0]);
                if (!facts.add(fact) || previousOwner != null) {
                    violations.add("FACT_OWNER_DUPLICATE: " + fact + " in "
                            + (previousOwner == null ? fields[0] : previousOwner) + " and " + fields[0]);
                }
            }
            Ownership value = new Ownership(fields[0], fields[1], fields[2], Set.copyOf(facts));
            if (result.putIfAbsent(value.module(), value) != null) {
                violations.add("OWNER_DUPLICATE: " + value.module());
            }
            if (!schemas.add(value.schema())) {
                violations.add("SCHEMA_DUPLICATE: " + value.schema());
            }
            if (!prefixes.add(value.tablePrefix())) {
                violations.add("TABLE_PREFIX_DUPLICATE: " + value.tablePrefix());
            }
        }
        return result;
    }

    private static void inspectMigration(
            Path sql,
            Path migrationRoot,
            Map<String, Ownership> ownership,
            Map<String, Path> versions,
            List<String> violations) throws IOException {
        Path relative = migrationRoot.relativize(sql);
        String owner = relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
        Ownership expected = ownership.get(owner);
        if (expected == null) {
            violations.add("UNKNOWN_OWNER: " + relative);
            return;
        }

        Matcher name = MIGRATION_NAME.matcher(sql.getFileName().toString());
        if (!name.matches() || !name.group(2).equals(owner)) {
            violations.add("MIGRATION_NAME_INVALID: " + relative);
            return;
        }
        Path previous = versions.putIfAbsent(name.group(1), relative);
        if (previous != null) {
            violations.add("MIGRATION_VERSION_DUPLICATE: V" + name.group(1) + " in " + previous + " and " + relative);
        }

        String content = Files.readString(sql, StandardCharsets.UTF_8);
        String parsedContent = sqlCodeOnly(content);
        inspectQualifiedIdentifiers(parsedContent, expected, relative, violations);
        Matcher table = TABLE_REFERENCE.matcher(parsedContent);
        while (table.find()) {
            String tableName = identifier(table.group(2));
            if (!tableName.startsWith(expected.tablePrefix())) {
                violations.add("TABLE_PREFIX_MISMATCH: " + relative + " -> " + tableName);
            }
        }
        Matcher additionalTable = ADDITIONAL_TABLE_REFERENCE.matcher(parsedContent);
        while (additionalTable.find()) {
            String schema = additionalTable.group(1) == null
                    ? expected.schema() : identifier(additionalTable.group(1));
            String tableName = identifier(additionalTable.group(2));
            if (!schema.equals(expected.schema())) {
                violations.add("CROSS_SCHEMA_REFERENCE: " + relative + " -> " + additionalTable.group());
            }
            if (!tableName.startsWith(expected.tablePrefix())) {
                violations.add("TABLE_PREFIX_MISMATCH: " + relative + " -> " + tableName);
            }
        }
        Matcher foreign = FOREIGN_REFERENCE.matcher(parsedContent);
        while (foreign.find()) {
            String schema = foreign.group(1) == null ? expected.schema() : identifier(foreign.group(1));
            String tableName = identifier(foreign.group(2));
            if (!schema.equals(expected.schema()) || !tableName.startsWith(expected.tablePrefix())) {
                violations.add("CROSS_MODULE_FOREIGN_KEY: " + relative + " -> " + foreign.group());
            }
        }
    }

    private static void inspectQualifiedIdentifiers(
            String content, Ownership expected, Path relative, List<String> violations) {
        for (String statement : sqlCodeOnly(content).split(";")) {
            List<ScopedAlias> aliases = new ArrayList<>();
            Matcher alias = TABLE_ALIAS.matcher(statement);
            while (alias.find()) {
                String aliasName = identifier(alias.group(1));
                if (!SQL_KEYWORDS.contains(aliasName)) {
                    aliases.add(new ScopedAlias(aliasName, scopePath(statement, alias.start())));
                }
            }
            Matcher qualified = QUALIFIED_IDENTIFIER.matcher(statement);
            while (qualified.find()) {
                String schema = identifier(qualified.group(1));
                List<Integer> referenceScope = scopePath(statement, qualified.start());
                boolean visibleAlias = aliases.stream().anyMatch(value ->
                        value.name().equals(schema) && isScopePrefix(value.scope(), referenceScope));
                if (visibleAlias) {
                    continue;
                }
                if (!schema.equals(expected.schema())
                        && !schema.equals("pg_catalog")
                        && !schema.equals("information_schema")) {
                    violations.add("CROSS_SCHEMA_REFERENCE: " + relative + " -> " + qualified.group());
                }
            }
        }
    }

    private static boolean isScopePrefix(List<Integer> aliasScope, List<Integer> referenceScope) {
        return aliasScope.size() <= referenceScope.size()
                && aliasScope.equals(referenceScope.subList(0, aliasScope.size()));
    }

    private static List<Integer> scopePath(String content, int limit) {
        List<Integer> scope = new ArrayList<>();
        int nextScope = 0;
        for (int index = 0; index < limit; index++) {
            if (content.charAt(index) == '(') {
                scope.add(++nextScope);
            } else if (content.charAt(index) == ')' && !scope.isEmpty()) {
                scope.removeLast();
            }
        }
        return List.copyOf(scope);
    }

    private static String sqlCodeOnly(String content) {
        StringBuilder result = new StringBuilder(content.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean singleQuoted = false;
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
            } else if (singleQuoted) {
                result.append(current == '\n' ? '\n' : ' ');
                if (current == '\'' && next == '\'') {
                    result.append(' ');
                    index++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
            } else if (current == '-' && next == '-') {
                result.append("  ");
                index++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result.append("  ");
                index++;
                blockComment = true;
            } else if (current == '\'') {
                result.append(' ');
                singleQuoted = true;
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String identifier(String token) {
        if (token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1).replace("\"\"", "\"");
        }
        return token.toLowerCase(Locale.ROOT);
    }

    record Ownership(String module, String schema, String tablePrefix, Set<String> factOwners) {}

    private record ScopedAlias(String name, List<Integer> scope) {}

    record Result(Map<String, Ownership> ownership, List<String> violations) {}
}
