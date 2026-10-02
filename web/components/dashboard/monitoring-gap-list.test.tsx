import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { MonitoringGap } from "@/lib/api/types";

import { MonitoringGapList } from "./monitoring-gap-list";

function gap(id: number, patch: Partial<MonitoringGap> = {}): MonitoringGap {
  return {
    id,
    startedAt: "2026-10-02T00:10:00Z",
    endedAt: "2026-10-02T02:40:00Z",
    missedSchedules: 2,
    ...patch,
  };
}

describe("MonitoringGapList", () => {
  it("shows each gap's period in KST, its length and the late schedules", () => {
    render(
      <MonitoringGapList
        gaps={[
          gap(1),
          gap(2, { startedAt: "2026-09-30T14:00:00Z", endedAt: "2026-09-30T15:30:00Z", missedSchedules: 1 }),
        ]}
        limit={5}
      />,
    );

    const items = within(screen.getByRole("list", { name: "모니터링 공백" })).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("10-02 09:10 ~ 11:40");
    expect(items[0]).toHaveTextContent("2시간 30분");
    expect(items[0]).toHaveTextContent("제때 실행되지 않은 스케줄 2건");
    // 날짜가 바뀌면 끝에도 날짜
    expect(items[1]).toHaveTextContent("09-30 23:00 ~ 10-01 00:30");
    expect(items[1]).toHaveTextContent("1시간 30분");
    expect(screen.queryByText(/건 중 최근/)).not.toBeInTheDocument();
  });

  it("shows only the latest gaps up to the limit and says how many there are", () => {
    render(<MonitoringGapList gaps={[1, 2, 3].map((id) => gap(id))} limit={2} />);

    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByText("3건 중 최근 2건")).toBeInTheDocument();
  });

  it("does not mention the limit when all gaps fit", () => {
    render(<MonitoringGapList gaps={[1, 2].map((id) => gap(id))} limit={2} />);

    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.queryByText(/건 중 최근/)).not.toBeInTheDocument();
  });
});
