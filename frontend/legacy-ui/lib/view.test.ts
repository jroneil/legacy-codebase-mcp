import { describe, expect, it } from "vitest";
import { asChain, describeTruncation, evidenceLocation, formatCount, formatTimestamp, intParam, nodeLabel, traceSteps } from "@/lib/view";
import { edge, path } from "../test/fixtures";

describe("trace presentation", () => {
  it("interleaves nodes and edges in backend order", () => {
    const chain = asChain(path());
    const steps = traceSteps(chain);
    expect(steps.map((step) => (step.kind === "node" ? step.id : `${step.edge.type}`))).toEqual([
      "java:type:demo.CustomerAction",
      "CALLS",
      "java:type:demo.CustomerService",
    ]);
  });

  it("keeps a trailing unresolved edge with its backend description", () => {
    const chain = asChain(
      path({
        nodes: ["java:type:demo.CustomerAction", "java:type:demo.CustomerService"],
        evidence: [
          edge(),
          edge({ targetId: null, targetDescription: "candidates: demo.CustomerDAO, demo.OtherDAO", type: "INJECTS", resolutionState: "UNRESOLVED" }),
        ],
        resolutionState: "UNRESOLVED",
        termination: "UNRESOLVED",
      }),
    );
    const steps = traceSteps(chain);
    expect(steps).toHaveLength(4);
    const last = steps[3];
    expect(last.kind).toBe("edge");
    if (last.kind === "edge") {
      expect(last.edge.targetId).toBeNull();
      expect(last.edge.targetDescription).toContain("demo.CustomerDAO");
    }
  });

  it("normalizes entry-point trace paths that use components", () => {
    const chain = asChain({ components: ["/customer/search", "demo.CustomerAction"], evidence: [edge({ type: "ROUTES_TO" })], resolutionState: "INFERRED", termination: "LEAF" });
    expect(chain.nodes).toEqual(["/customer/search", "demo.CustomerAction"]);
    expect(traceSteps(chain)[1]).toMatchObject({ kind: "edge" });
  });
});

describe("labels and formatting", () => {
  it("shortens known stable ID prefixes without changing the identity", () => {
    expect(nodeLabel("java:type:demo.CustomerAction")).toBe("demo.CustomerAction");
    expect(nodeLabel("spring:bean:web/WEB-INF/applicationContext.xml#customerService")).toBe("customerService");
    expect(nodeLabel("db:table:CUSTOMER")).toBe("CUSTOMER");
    expect(nodeLabel("/customer/search")).toBe("/customer/search");
    // Grails/Groovy identities added in Slice 8 keep the same presentation rules.
    expect(nodeLabel("groovy:type:demo.CustomerController")).toBe("demo.CustomerController");
    expect(nodeLabel("groovy:method:demo.CustomerController#show(Long)")).toBe("demo.CustomerController#show(Long)");
    expect(nodeLabel("grails:route:grails-app/controllers/demo/UrlMappings.groovy#/customer/$id")).toBe("/customer/$id");
  });

  it("renders evidence file, line and column as provided", () => {
    expect(evidenceLocation(edge({ sourcePath: "web/WEB-INF/struts-config.xml", line: 42, column: 3 }))).toBe(
      "web/WEB-INF/struts-config.xml:42:3",
    );
  });

  it("formats backend timestamps and null freshness without inventing values", () => {
    expect(formatTimestamp("2026-10-01T12:00:00.123456Z")).toBe("2026-10-01T12:00:00Z");
    expect(formatTimestamp(null)).toBe("—");
    expect(formatCount(0)).toBe("0");
    expect(formatCount(null)).toBe("—");
  });

  it("describes truncation reasons and falls back when none are supplied", () => {
    expect(describeTruncation(["DEPTH_LIMIT", "RESULT_LIMIT"])).toContain("traversal depth bound");
    expect(describeTruncation([])).toContain("Results are incomplete");
  });

  it("clamps invalid URL bounds to the default", () => {
    expect(intParam("7", 12, 0, 16)).toBe(7);
    expect(intParam("99", 12, 0, 16)).toBe(12);
    expect(intParam(undefined, 12, 0, 16)).toBe(12);
    expect(intParam("abc", 12, 0, 16)).toBe(12);
  });
});
