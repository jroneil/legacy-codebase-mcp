package com.oneil.legacy.mcp;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/** Transport startup and handshake smoke test over the Streamable HTTP endpoint at /mcp. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTransportSmokeTests extends McpIntegrationSupport {
    private static final Set<String> TOOLS = Set.of("search_symbols", "get_symbol", "find_usages", "trace_component",
            "list_database_tables", "find_table_usages", "inspect_location", "list_entry_points");

    @BeforeEach
    void fixture() {
        reset();
        publish(McpTestFixture.standard());
    }

    @Test
    void initializeHandshakeAdvertisesToolsCapability() throws Exception {
        HttpResponse<String> response = post(1, "initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"slice6-test\",\"version\":\"1\"}}", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isPresent();
        JsonNode result = payload(response.body()).path("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("legacy-codebase-mcp");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("capabilities").has("prompts")).isFalse();
        assertThat(result.path("capabilities").has("resources")).isFalse();
    }

    @Test
    void toolsListExposesExactlyTheDeterministicReadOnlyTools() throws Exception {
        String session = session();
        JsonNode tools = call(session, 2, "tools/list", "{}").path("result").path("tools");
        assertThat(toolNames(tools)).containsExactlyInAnyOrderElementsOf(TOOLS);
        for (JsonNode tool : tools) {
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        }
        JsonNode inspect = StreamSupport.stream(tools.spliterator(), false)
                .filter(tool -> tool.path("name").asText().equals("inspect_location")).findFirst().orElseThrow();
        assertThat(StreamSupport.stream(inspect.path("inputSchema").path("required").spliterator(), false)
                .map(JsonNode::asText)).containsExactlyInAnyOrder("path", "line");
    }

    @Test
    void toolCallRoundTripsBoundedEvidenceThroughTheTransport() throws Exception {
        String session = session();
        JsonNode result = call(session, 3, "tools/call",
                "{\"name\":\"list_database_tables\",\"arguments\":{\"limit\":1}}").path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode body = mapper.readTree(result.path("content").get(0).path("text").asText());
        assertThat(body.path("error").isNull()).isTrue();
        assertThat(body.path("returnedCount").asInt()).isEqualTo(1);
        assertThat(body.path("totalCount").asInt()).isEqualTo(2);
        assertThat(body.path("truncated").asBoolean()).isTrue();
        assertThat(body.path("nextCursor").asText()).isEqualTo("1");
        assertThat(body.path("freshness").path("scanId").asText()).isNotBlank();
    }

    @Test
    void unknownToolIsRejectedByTheTransport() throws Exception {
        String session = session();
        JsonNode response = call(session, 4, "tools/call", "{\"name\":\"does_not_exist\",\"arguments\":{}}");
        assertThat(response.has("error")).isTrue();
    }

    private String session() throws Exception {
        HttpResponse<String> response = post(1, "initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"slice6-test\",\"version\":\"1\"}}", null);
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(null, "notifications/initialized", "{}", session);
        return session;
    }

    private JsonNode call(String session, int id, String method, String params) throws Exception {
        return payload(post(id, method, params, session).body());
    }

    private HttpResponse<String> post(Integer id, String method, String params, String session) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\"," + (id == null ? "" : "\"id\":" + id + ",") + "\"method\":\"" + method
                + "\",\"params\":" + params + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) request.header("Mcp-Session-Id", session);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Streamable HTTP may answer with a JSON body or a single SSE data frame. */
    private JsonNode payload(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) return mapper.readTree(trimmed);
        int index = trimmed.indexOf("data:");
        return mapper.readTree(index < 0 ? "{}" : trimmed.substring(index + 5).trim());
    }

    private static List<String> toolNames(JsonNode tools) {
        return StreamSupport.stream(tools.spliterator(), false).map(tool -> tool.path("name").asText()).toList();
    }
}
