import { describe, expect, it } from "vitest";

import { splitExtractions } from "./extraction";

describe("splitExtractions", () => {
  it("separates extraction results from assertion results", () => {
    const status = { description: "status in [2xx]", passed: true, message: "was 200" };

    const { assertions, extractions } = splitExtractions([
      status,
      { description: "extract {{token}} from $.data.token", passed: true, message: "extracted (hidden)" },
      { description: "extract {{orderUrl}} from header Location", passed: true, message: 'was "/orders/7"' },
      { description: "extract {{id}} from $.items[?(@.sku == 'a')].id", passed: false, message: "path not found" },
    ]);

    expect(assertions).toEqual([status]);
    expect(extractions).toEqual([
      {
        name: "token",
        from: "BODY",
        expression: "$.data.token",
        passed: true,
        value: null,
        hidden: true,
        failure: null,
      },
      {
        name: "orderUrl",
        from: "HEADER",
        expression: "Location",
        passed: true,
        value: "/orders/7",
        hidden: false,
        failure: null,
      },
      {
        name: "id",
        from: "BODY",
        expression: "$.items[?(@.sku == 'a')].id",
        passed: false,
        value: null,
        hidden: false,
        failure: "path not found",
      },
    ]);
  });

  it("keeps quotes inside values and shortened values as they are", () => {
    const { extractions } = splitExtractions([
      { description: "extract {{a}} from $.a", passed: true, message: 'was "say "hi""' },
      { description: "extract {{b}} from $.b", passed: true, message: 'was "xxxx...' },
    ]);

    expect(extractions.map((extraction) => extraction.value)).toEqual(['say "hi"', "xxxx..."]);
  });

  it("leaves rules that only look similar among the assertions", () => {
    const rule = { description: '$.extract == "{{x}}"', passed: true, message: "was 1" };

    expect(splitExtractions([rule])).toEqual({ assertions: [rule], extractions: [] });
  });
});
