import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { PendingSchemaChange } from "@/lib/api/types";

import { PendingSchemaTable } from "./pending-schema-table";

function pending(id: number, patch: Partial<PendingSchemaChange> = {}): PendingSchemaChange {
  return {
    id,
    run: { id: id * 100, startedAt: "2026-10-31T01:05:00Z" },
    testCase: { id: id * 10, name: `case-${id}` },
    suite: { id: 3, name: "결제" },
    project: { id: 1, name: "결제 서비스" },
    environment: { id: 7, name: "dev" },
    changes: [
      { level: "BREAKING", kind: "TYPE_CHANGED", path: "$.id", expected: ["number"], actual: ["string"] },
      { level: "WARNING", kind: "NULLABLE", path: "$.name", expected: ["string"], actual: ["null"] },
    ],
    ...patch,
  };
}

describe("PendingSchemaTable", () => {
  it("links each case to the run with the pending result and counts its changes by level", () => {
    render(<PendingSchemaTable changes={[pending(1)]} limit={10} />);

    const row = screen.getByRole("row", { name: /case-1/ });
    expect(within(row).getByRole("link", { name: "case-1" })).toHaveAttribute("href", "/runs/100");
    expect(row).toHaveTextContent("결제 · dev");
    expect(row).toHaveTextContent("호환 깨짐 1");
    expect(row).toHaveTextContent("주의 1");
    expect(row).toHaveTextContent("2026-10-31 10:05:00");
    expect(screen.queryByText(/건 중/)).not.toBeInTheDocument();
  });

  it("shows only the limit and says how many there are", () => {
    render(<PendingSchemaTable changes={[1, 2, 3].map((id) => pending(id))} limit={2} />);

    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getByText("3건 중 2건 (심한 변화 순)")).toBeInTheDocument();
  });
});
