package com.oneil.legacy.mcp;

import com.oneil.legacy.scan.ScanModel;
import com.oneil.legacy.symbol.JavaIndexModel.Index;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.JavaIndexModel.Symbol;
import java.util.ArrayList;
import java.util.List;

/** Deterministic Slice 6 MCP fixture: a Struts route through Spring beans to two database tables. */
final class McpTestFixture {
    static final String ROUTE = "/customer/search";
    static final String ACTION = "java:type:demo.CustomerAction";
    static final String EXECUTE = "java:method:demo.CustomerAction#execute()";
    static final String SERVICE = "java:type:demo.CustomerService";
    static final String DAO = "java:type:demo.CustomerDAO";
    static final String DYNAMIC = "java:type:demo.Dynamic";
    static final String TABLE = "db:table:CUSTOMER";
    static final String ORDERS = "db:table:ORDERS";
    static final String ACTION_PATH = "src/demo/CustomerAction.java";
    static final String SERVICE_PATH = "src/demo/CustomerService.java";
    static final String DAO_PATH = "src/demo/CustomerDAO.java";
    static final String ROUTE_PATH = "web/WEB-INF/struts-config.xml";

    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Relationship> edges = new ArrayList<>();

    static McpTestFixture standard() {
        McpTestFixture fixture = new McpTestFixture();
        fixture.symbol(ROUTE, "ROUTE", "/customer/search", ROUTE_PATH, 5, 5, "RESOLVED", "operation=search");
        fixture.symbol(ACTION, "CLASS", "CustomerAction", ACTION_PATH, 1, 60, "RESOLVED", null);
        fixture.symbol(EXECUTE, "METHOD", "demo.CustomerAction#execute()", ACTION_PATH, 10, 25, "RESOLVED", "()");
        fixture.symbol(SERVICE, "CLASS", "CustomerService", SERVICE_PATH, 1, 40, "RESOLVED", null);
        fixture.symbol(DAO, "CLASS", "CustomerDAO", DAO_PATH, 1, 30, "RESOLVED", null);
        fixture.symbol(TABLE, "DATABASE_TABLE", "CUSTOMER", DAO_PATH, 40, 40, "RESOLVED", null);
        fixture.symbol(ORDERS, "DATABASE_TABLE", "ORDERS", DAO_PATH, 42, 42, "RESOLVED", null);
        fixture.symbol(DYNAMIC, "QUERY_ARTIFACT", "Dynamic", DAO_PATH, 44, 44, "UNRESOLVED", "dynamic sql");
        fixture.edge(ROUTE, ACTION, "ROUTES_TO", "RESOLVED", null, ROUTE_PATH, 6);
        fixture.edge(ACTION, SERVICE, "CALLS", "RESOLVED", null, ACTION_PATH, 12);
        fixture.edge(EXECUTE, SERVICE, "CALLS", "RESOLVED", null, ACTION_PATH, 14);
        fixture.edge(ACTION, EXECUTE, "CONTAINS", "RESOLVED", null, ACTION_PATH, 10);
        fixture.edge(SERVICE, DAO, "CALLS", "INFERRED", null, SERVICE_PATH, 20);
        fixture.edge(DAO, TABLE, "READS_TABLE", "RESOLVED", null, DAO_PATH, 40);
        fixture.edge(DAO, ORDERS, "WRITES_TABLE", "RESOLVED", null, DAO_PATH, 42);
        fixture.edge(SERVICE, null, "INJECTS", "UNRESOLVED", "candidates: " + DAO, SERVICE_PATH, 22);
        fixture.edge(DAO, DYNAMIC, "EXECUTES_QUERY", "RESOLVED", null, DAO_PATH, 44);
        return fixture;
    }

    McpTestFixture symbol(String stableId, String kind, String qualifiedName, String sourcePath,
                          int startLine, int endLine, String resolutionState, String signature) {
        String simpleName = qualifiedName.contains(".") ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1) : qualifiedName;
        symbols.add(new Symbol(stableId, kind, simpleName, qualifiedName, signature, resolutionState, sourcePath,
                startLine, 1, endLine, 1));
        return this;
    }

    McpTestFixture edge(String sourceId, String targetId, String type, String resolutionState, String description,
                        String sourcePath, int line) {
        edges.add(new Relationship(sourceId, targetId, description, type, resolutionState, sourcePath, line, 1, "FIXTURE"));
        return this;
    }

    List<Symbol> symbols() {
        return List.copyOf(symbols);
    }

    ScanModel.Inventory inventory() {
        List<ScanModel.SourceFile> files = List.of(ROUTE_PATH, ACTION_PATH, SERVICE_PATH, DAO_PATH).stream()
                .map(path -> new ScanModel.SourceFile(path, path.endsWith(".xml") ? "XML" : "JAVA", "a".repeat(64), "UTF-8", 100L))
                .toList();
        return new ScanModel.Inventory(files, List.of(), "b".repeat(40), new Index(symbols, edges, List.of()));
    }
}
