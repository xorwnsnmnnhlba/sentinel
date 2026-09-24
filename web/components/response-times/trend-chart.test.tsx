import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import type { ResponseTimePoint } from "@/lib/api/types";

import { TrendChart } from "./trend-chart";

function point(
  date: string,
  p50: number | null,
  p95: number | null,
  samples = p50 === null ? 0 : 3,
): ResponseTimePoint {
  return { date, samples, p50, p95, max: p95 === null ? null : p95 + 10 };
}

const points = [
  point("2026-09-20", 100, 300),
  point("2026-09-21", 120, 340),
  point("2026-09-22", null, null),
  point("2026-09-23", 110, 900),
];

describe("TrendChart", () => {
  it("draws both series with a legend and breaks the line on empty days", () => {
    const { container } = render(<TrendChart points={points} title="주문 조회 일별 응답시간" />);

    const legend = screen.getByRole("list", { name: "범례" });
    expect(within(legend).getByText("p50")).toBeInTheDocument();
    expect(within(legend).getByText("p95")).toBeInTheDocument();
    const paths = container.querySelectorAll("path");
    expect(paths).toHaveLength(2);
    // 값이 없는 날 이후 새 구간 시작
    paths.forEach((path) => expect(path.getAttribute("d")?.match(/M/g)).toHaveLength(2));
    // 마지막 날 한 점은 이어질 선이 없어 표식으로 표시
    expect(container.querySelectorAll("circle")).toHaveLength(2);
  });

  it("shows every series of a day from the keyboard", async () => {
    const user = userEvent.setup();
    render(<TrendChart points={points} title="주문 조회 일별 응답시간" />);

    // 포커스하면 마지막으로 값이 있는 날
    await user.tab();
    await user.tab();
    const tooltip = screen.getByRole("status");
    expect(tooltip).toHaveTextContent("2026-09-23 · 3건");
    expect(tooltip).toHaveTextContent("110ms");
    expect(tooltip).toHaveTextContent("900ms");
    expect(tooltip).toHaveTextContent("910ms");

    await user.keyboard("{ArrowLeft}");
    expect(screen.getByRole("status")).toHaveTextContent("응답 없음");
    await user.keyboard("{Home}");
    expect(screen.getByRole("status")).toHaveTextContent("2026-09-20");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("switches to a table with the same values", async () => {
    const user = userEvent.setup();
    render(<TrendChart points={points} title="주문 조회 일별 응답시간" />);

    await user.click(screen.getByRole("button", { name: "표로 보기" }));

    const rows = screen.getAllByRole("row");
    expect(rows).toHaveLength(5);
    expect(rows[4]).toHaveTextContent("2026-09-23");
    expect(rows[4]).toHaveTextContent("900ms");
    expect(rows[3]).toHaveTextContent("–");
    await user.click(screen.getByRole("button", { name: "차트로 보기" }));
    expect(screen.getByRole("img", { name: /주문 조회 일별 응답시간/ })).toBeInTheDocument();
  });

  it("says so when the period has no responses", () => {
    render(<TrendChart points={[point("2026-09-20", null, null)]} title="빈 기간" />);

    expect(screen.getByText("이 기간에 응답을 받은 실행이 없습니다")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "표로 보기" })).not.toBeInTheDocument();
  });
});
