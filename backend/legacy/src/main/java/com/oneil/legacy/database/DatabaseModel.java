package com.oneil.legacy.database;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Database entities are normalized symbol kinds; metadata occupies the existing signature column. */
public final class DatabaseModel {
    private DatabaseModel() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record DatabaseTable(String qualifiedName) {
        public String stableId() { return "db:table:" + qualifiedName; }
    }
    public record QueryArtifact(String language, String statementType, String parseStatus,
                                String sourceExpression, String sqlTemplate, List<String> partialLiterals,
                                String procedureName) {
        public String metadata() { return JSON.writeValueAsString(this); }
    }
    /** Linear redaction also handles incomplete literals: parser failure must not expose their values. */
    public static String safeSql(String value) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (value.startsWith("--", i)) {
                int end = value.indexOf('\n', i + 2); i = end < 0 ? value.length() : end;
                safe.append(" ");
            } else if (value.startsWith("/*", i)) {
                int end = value.indexOf("*/", i + 2); i = end < 0 ? value.length() : end + 2;
                safe.append(" ");
            } else if ((c == 'q' || c == 'Q') && i + 2 < value.length() && value.charAt(i + 1) == '\'') {
                char close = switch (value.charAt(i + 2)) { case '[' -> ']'; case '{' -> '}'; case '(' -> ')'; case '<' -> '>'; default -> value.charAt(i + 2); };
                int end = value.indexOf("" + close + '\'', i + 3); i = end < 0 ? value.length() : end + 2;
                safe.append("'?'");
            } else if (c == '\'' || c == '"') {
                // Double-quoted values/identifiers are hidden in previews; parsed table identities remain separate.
                char quote = c; i++;
                while (i < value.length()) {
                    char next = value.charAt(i++);
                    if (next == '\\' && i < value.length()) { i++; continue; }
                    if (next == quote) {
                        if (i < value.length() && value.charAt(i) == quote) { i++; continue; }
                        break;
                    }
                }
                safe.append(quote).append('?').append(quote);
            } else if (c == '$' && dollarTagEnd(value, i) >= 0) {
                int tagEnd = dollarTagEnd(value, i);
                String delimiter = value.substring(i, tagEnd + 1);
                int end = value.indexOf(delimiter, tagEnd + 1); i = end < 0 ? value.length() : end + delimiter.length();
                safe.append("'?'");
            } else {
                safe.append(Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' ? '?' : c); i++;
            }
        }
        return safe.toString();
    }
    private static int dollarTagEnd(String value, int start) {
        for (int i = start + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '$') return i;
            if (!(Character.isLetter(c) || c == '_' || i > start + 1 && Character.isDigit(c))) break;
        }
        return -1;
    }
}
