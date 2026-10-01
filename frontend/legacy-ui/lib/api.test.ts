import { afterEach, describe, expect, it, vi } from "vitest";
import { encodeId, decodeId, searchSymbols, scanDetail, symbolDetail } from "@/lib/api";
import { freshness, page, scanDetail as scanDetailFixture, symbol } from "../test/fixtures";

function mockFetch(handler: (url: URL) => Response | Promise<Response>) {
  const mock = vi.fn(async (input: RequestInfo | URL) => {
    const url = input instanceof URL ? input : new URL(typeof input === "string" ? input : input.url);
    return handler(url);
  });
  vi.stubGlobal("fetch", mock);
  return mock;
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("api client", () => {
  it("calls the documented endpoint with bounded and encoded query parameters", async () => {
    const mock = mockFetch(() => json({ freshness: freshness(), candidates: page([symbol()]) }));
    const result = await searchSymbols("Customer Service", 100, 0);
    expect(result.ok).toBe(true);
    const url = mock.mock.calls[0][0] as URL;
    expect(url.pathname).toBe("/api/symbols/search");
    expect(url.searchParams.get("q")).toBe("Customer Service");
    expect(url.searchParams.get("limit")).toBe("100");
  });

  it("does not send empty optional parameters", async () => {
    const mock = mockFetch(() => json({ freshness: freshness(), candidates: page([]) }));
    await searchSymbols("", 100, 0);
    const url = mock.mock.calls[0][0] as URL;
    expect(url.searchParams.has("q")).toBe(false);
  });

  it("returns backend evidence unchanged on success", async () => {
    mockFetch(() => json({ freshness: freshness({ scanId: "scan-1" }), candidates: page([symbol({ stableId: "java:type:demo.A" })]) }));
    const result = await searchSymbols("A");
    expect(result).toEqual({
      ok: true,
      data: { freshness: freshness({ scanId: "scan-1" }), candidates: page([symbol({ stableId: "java:type:demo.A" })]) },
    });
  });

  it("reports the backend error message with its status for failures", async () => {
    mockFetch(() => json({ error: "Symbol not found in an active completed scan." }, 404));
    const missing = await symbolDetail("java:type:demo.Missing");
    expect(missing).toEqual({ ok: false, status: 404, error: "Not found: Symbol not found in an active completed scan." });

    mockFetch(() => json({ error: "limit must be 1..200 and offset 0..1000000." }, 400));
    const invalid = await symbolDetail("java:type:demo.A");
    expect(invalid.ok).toBe(false);
    if (!invalid.ok) expect(invalid.error).toContain("Invalid request");
  });

  it("reports an unreachable backend instead of throwing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("fetch failed");
      }),
    );
    const result = await searchSymbols("Customer");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error).toContain("Backend unreachable");
  });

  it("percent-encodes stable IDs used in a path segment", async () => {
    const mock = mockFetch(() => json(scanDetailFixture()));
    expect(encodeId("java:method:demo.A#run(java.lang.Long)")).toBe("java%3Amethod%3Ademo.A%23run(java.lang.Long)");
    await scanDetail("11111111-1111-1111-1111-111111111111");
    const url = mock.mock.calls[0][0] as URL;
    expect(url.pathname).toBe("/api/scans/11111111-1111-1111-1111-111111111111");
  });

  it("decodes dynamic route segments back into the original stable ID", () => {
    expect(decodeId(encodeId("/customer/search"))).toBe("/customer/search");
    expect(decodeId(encodeId("spring:bean:web/WEB-INF/applicationContext.xml#customerService"))).toBe(
      "spring:bean:web/WEB-INF/applicationContext.xml#customerService",
    );
    expect(decodeId("java%3Atype%3Ademo.A")).toBe("java:type:demo.A");
    expect(decodeId("literal%zz")).toBe("literal%zz");
  });
});
