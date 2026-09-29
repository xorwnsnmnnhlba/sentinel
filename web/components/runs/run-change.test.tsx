import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { RunChange, RunDetail, RunResult } from "@/lib/api/types";

import { ChangeBadge, ChangeSummary } from "./run-change";

function run(patch: Partial<RunDetail>, changes: (RunChange | null)[] = []): RunDetail {
  return {
    testCase: null,
    throughCase: null,
    rerunOf: null,
    previousRun: { id: 41, startedAt: "2026-09-27T00:00:00Z" },
    results: changes.map((change) => ({ change }) as RunResult),
    ...patch,
  } as RunDetail;
}

describe("ChangeSummary", () => {
  it("links the previous run and counts the changes", () => {
    render(<ChangeSummary run={run({}, ["NEWLY_FAILING", "NEWLY_FAILING", "RECOVERED", "UNCHANGED"])} />);

    expect(screen.getByRole("link", { name: "직전 실행 #41" })).toHaveAttribute("href", "/runs/41");
    expect(screen.getByText("2026-09-27 09:00:00", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("새로 실패 2")).toBeInTheDocument();
    expect(screen.getByText("복구 1")).toBeInTheDocument();
    expect(screen.queryByText(/계속 실패/)).not.toBeInTheDocument();
  });

  it("says so when nothing changed", () => {
    render(<ChangeSummary run={run({}, ["UNCHANGED"])} />);

    expect(screen.getByText("판정 변화 없음")).toBeInTheDocument();
  });

  it("explains a first run and stays silent for partial runs", () => {
    const { container, unmount } = render(<ChangeSummary run={run({ previousRun: null })} />);
    expect(screen.getByText(/완료된 이전 실행이 없어 비교하지 않습니다/)).toBeInTheDocument();
    unmount();

    for (const partial of [
      { testCase: { id: 1, name: "a" } },
      { throughCase: { id: 1, name: "a" } },
      { rerunOf: 40 },
    ]) {
      const rendered = render(<ChangeSummary run={run({ ...partial, previousRun: null })} />);
      expect(rendered.container).toBeEmptyDOMElement();
      rendered.unmount();
    }
    expect(container).toBeEmptyDOMElement();
  });
});

describe("ChangeBadge", () => {
  it.each([
    ["NEWLY_FAILING", "새로 실패"],
    ["RECOVERED", "복구"],
    ["STILL_FAILING", "계속 실패"],
    ["NEW", "새 케이스"],
  ] as const)("labels %s", (change, label) => {
    render(<ChangeBadge change={change} />);

    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it("shows nothing for unchanged or uncompared cases", () => {
    const { container } = render(
      <>
        <ChangeBadge change="UNCHANGED" />
        <ChangeBadge change={null} />
      </>,
    );

    expect(container).toBeEmptyDOMElement();
  });
});
