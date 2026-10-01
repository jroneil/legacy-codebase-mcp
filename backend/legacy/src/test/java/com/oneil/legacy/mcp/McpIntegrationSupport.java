package com.oneil.legacy.mcp;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.scan.ScanStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class McpIntegrationSupport extends PostgresTestSupport {
    @Autowired ScanStore scans;
    @Autowired JdbcTemplate jdbc;
    @Autowired McpTools tools;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    /** Clears the shared test database and installs an empty active pointer, as other integration suites do. */
    void reset() {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
    }

    void clearActiveScan() {
        jdbc.update("UPDATE active_scan SET scan_id=NULL WHERE singleton=true");
    }

    UUID publish(McpTestFixture fixture) {
        UUID id = scans.create("fixture", "slice6-test");
        scans.start(id);
        scans.complete(id, fixture.inventory());
        return id;
    }

    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
