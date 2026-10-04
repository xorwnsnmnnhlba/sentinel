import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { FailureCauseSummary } from "@/lib/api/types";

import { FailureCauseList } from "./failure-cause-list";

function summary(patch: Partial<FailureCauseSummary> = {}): FailureCauseSummary {
  return {
    cause: "TIMEOUT",
    group: "NETWORK",
    results: 12,
    caseCount: 3,
    cases: [
      {
        testCase: { id: 10, name: "결제 승인" },
        suite: { id: 3, name: "결제" },
        environment: { id: 7, name: "dev" },
        results: 8,
      },
    ],
    ...patch,
  };
}

describe("FailureCauseList", () => {
  it("shows each cause with its group, share of all failures and the case with the most failures", () => {
    render(
      <FailureCauseList
        causes={[
          summary({ results: 1200 }),
          summary({ cause: "SERVER_ERROR", group: "API", results: 600, caseCount: 1 }),
        ]}
        total={3000}
        from="2026-09-28"
        to="2026-10-04"
      />,
    );

    const items = within(screen.getByRole("list", { name: "실패 원인" })).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("타임아웃");
    expect(items[0]).toHaveTextContent("GW·네트워크");
    expect(items[0]).toHaveTextContent("1,200건 · 40%");
    expect(items[0]).toHaveTextContent("결제 승인 (dev) 외 2");
    expect(within(items[0]).getByRole("link", { name: "결제 승인" })).toHaveAttribute(
      "href",
      "/runs?suiteId=3&environmentId=7&failing=true&from=2026-09-28&to=2026-10-04",
    );
    expect(items[1]).toHaveTextContent("서버 오류 5xx");
    expect(items[1]).toHaveTextContent("API");
    expect(items[1]).toHaveTextContent("600건 · 20%");
    expect(items[1]).not.toHaveTextContent("외");
  });
});
