import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { UnstableCase } from "@/lib/api/types";

import { UnstableCaseTable } from "./unstable-case-table";

function unstable(id: number, patch: Partial<UnstableCase> = {}): UnstableCase {
  return {
    testCase: { id, name: `case-${id}` },
    suite: { id: 3, name: "결제" },
    environment: { id: 7, name: "dev" },
    runs: 144,
    failures: 5,
    failureEpisodes: 3,
    retriedRuns: 0,
    ...patch,
  };
}

describe("UnstableCaseTable", () => {
  it("links each case to the run history of its suite and environment for the period", () => {
    render(
      <UnstableCaseTable cases={[unstable(1, { retriedRuns: 2 })]} from="2026-09-24" to="2026-09-30" limit={10} />,
    );

    const row = screen.getByRole("row", { name: /case-1/ });
    expect(within(row).getByRole("link", { name: "case-1" })).toHaveAttribute(
      "href",
      "/runs?suiteId=3&environmentId=7&from=2026-09-24&to=2026-09-30",
    );
    expect(row).toHaveTextContent("결제 · dev");
    expect(row).toHaveTextContent("실패 구간 3회 · 재시도 2회");
    expect(row).toHaveTextContent("5 / 144");
  });

  it("shows only the limit and says how many there are", () => {
    render(
      <UnstableCaseTable cases={[1, 2, 3].map((id) => unstable(id))} from="2026-09-24" to="2026-09-30" limit={2} />,
    );

    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getByText("3건 중 2건 (실패 구간·재시도가 많은 순)")).toBeInTheDocument();
  });
});
