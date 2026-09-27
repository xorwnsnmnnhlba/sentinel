import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ResultDrift } from "@/lib/api/types";

import { DriftBadge, DriftSummary, highestLevel } from "./drift";

const changed: ResultDrift = {
  status: "COMPARED",
  observations: 5,
  changes: [
    { level: "BREAKING", kind: "REMOVED", path: "$.id", expected: ["number"], actual: [] },
    { level: "WARNING", kind: "NULLABLE", path: "$.name", expected: ["string"], actual: ["null"] },
    { level: "INFO", kind: "ADDED", path: "$.meta", expected: [], actual: ["object"] },
  ],
};

describe("drift", () => {
  it("takes the most severe level from the ordered changes", () => {
    expect(highestLevel(changed)).toBe("BREAKING");
    expect(highestLevel({ ...changed, changes: changed.changes.slice(2) })).toBe("INFO");
    expect(highestLevel({ status: "COMPARED", observations: 5, changes: [] })).toBeNull();
    expect(highestLevel(null)).toBeNull();
  });

  it("shows a badge only when something changed", () => {
    const { rerender } = render(<DriftBadge drift={changed} />);
    expect(screen.getByText(/구조 변화 3/)).toHaveTextContent("가장 심한 등급: 호환 깨짐");

    rerender(<DriftBadge drift={{ status: "COLLECTING", observations: 1, changes: [] }} />);
    expect(screen.queryByText(/구조 변화/)).not.toBeInTheDocument();
    rerender(<DriftBadge drift={null} />);
    expect(screen.queryByText(/구조 변화/)).not.toBeInTheDocument();
  });

  it("lists each change with its level, kind and types", () => {
    render(<DriftSummary drift={changed} />);

    const items = within(screen.getByRole("list", { name: "구조 변화" })).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual([
      "호환 깨짐필수 필드 없음$.idnumber → 없음",
      "주의null 등장$.namestring → null",
      "새 필드새 필드$.meta없음 → object",
    ]);
    expect(screen.getByText("이전 실행 5회를 기준으로 비교한 변화입니다")).toBeInTheDocument();
  });

  it("explains when there is nothing to compare yet or nothing changed", () => {
    const { rerender } = render(<DriftSummary drift={{ status: "COLLECTING", observations: 2, changes: [] }} />);
    expect(screen.getByText(/아직 비교하지 않습니다 \(같은 케이스·환경의 이전 실행 2회\)/)).toBeInTheDocument();

    rerender(<DriftSummary drift={{ status: "COMPARED", observations: 4, changes: [] }} />);
    expect(screen.getByText("이전 실행 4회와 구조가 같습니다")).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});
