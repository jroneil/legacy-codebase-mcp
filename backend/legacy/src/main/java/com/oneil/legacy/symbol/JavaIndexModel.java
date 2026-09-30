package com.oneil.legacy.symbol;

import com.oneil.legacy.scan.ScanModel.AnalysisError;
import java.util.List;

public final class JavaIndexModel {
    private JavaIndexModel() {}
    public record Symbol(String stableId, String kind, String simpleName, String qualifiedName,
                         String signature, String resolutionState, String sourcePath,
                         int startLine, int startColumn, int endLine, int endColumn) {}
    public record Relationship(String sourceId, String targetId, String targetDescription, String type,
                               String resolutionState, String sourcePath, int line, int column, String evidenceType) {}
    public record Index(List<Symbol> symbols, List<Relationship> relationships, List<AnalysisError> errors) {
        public Index {
            symbols = List.copyOf(symbols);
            relationships = List.copyOf(relationships);
            errors = List.copyOf(errors);
        }
        public static Index empty() { return new Index(List.of(), List.of(), List.of()); }
    }
}
