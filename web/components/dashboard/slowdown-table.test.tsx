import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Slowdown } from "@/lib/api/types";

import { SlowdownTable } from "./slowdown-table";

function slowdown(id: number, patch: Partial<Slowdown> = {}): Slowdown {
  return {
    id,
    testCase: { id: id * 10, name: `case-${id}` },
    suite: { id: 3, name: "결제" },
    project: { id: 1, name: "결제 서비스" },
    environment: { id: 7, name: "dev" },
    baselineP95: 320,
    detectedP95: 610,
    increase: 0.906,
    detectedAt: "2026-10-04T02:05:00Z",
    ...patch,
  };
}

describe("SlowdownTable", () => {
  it("links each case to its response time trend and shows the change at detection in KST", () => {
    render(<SlowdownTable slowdowns={[slowdown(1)]} limit={10} />);

    const row = screen.getByRole("row", { name: /case-1/ });
    expect(within(row).getByRole("link", { name: "case-1" })).toHaveAttribute(
      "href",
      "/suites/3?environmentId=7&caseId=10#response-times",
    );
    expect(row).toHaveTextContent("결제 · dev");
    expect(row).toHaveTextContent("320ms → 610ms");
    expect(row).toHaveTextContent("+91% 느려짐");
    expect(row).toHaveTextContent("2026-10-04 11:05:00");
    expect(screen.queryByText(/건 중/)).not.toBeInTheDocument();
  });

  it("shows only the limit and says how many there are", () => {
    render(<SlowdownTable slowdowns={[1, 2, 3].map((id) => slowdown(id))} limit={2} />);

    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getByText("3건 중 2건 (늘어난 비율이 큰 순)")).toBeInTheDocument();
  });

  it("does not mention the limit when all fit", () => {
    render(<SlowdownTable slowdowns={[1, 2].map((id) => slowdown(id))} limit={2} />);

    expect(screen.queryByText(/건 중/)).not.toBeInTheDocument();
  });
});
