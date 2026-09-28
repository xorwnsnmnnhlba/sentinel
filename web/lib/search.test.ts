import { describe, expect, it } from "vitest";

import { splitMatches } from "./search";

describe("splitMatches", () => {
  it("marks every occurrence ignoring case and keeps the original text", () => {
    expect(splitMatches("/Billing/v1/billing", "billing")).toEqual([
      { text: "/", match: false },
      { text: "Billing", match: true },
      { text: "/v1/", match: false },
      { text: "billing", match: true },
    ]);
  });

  it("matches the trimmed query and handles matches at both ends", () => {
    expect(splitMatches("charge", " charge ")).toEqual([{ text: "charge", match: true }]);
    expect(splitMatches("결제 승인", "승인")).toEqual([
      { text: "결제 ", match: false },
      { text: "승인", match: true },
    ]);
  });

  it("leaves text without a match or query as is", () => {
    expect(splitMatches("/health", "login")).toEqual([{ text: "/health", match: false }]);
    expect(splitMatches("/health", "  ")).toEqual([{ text: "/health", match: false }]);
  });
});
