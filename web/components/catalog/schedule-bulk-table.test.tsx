import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";
import type { ScheduleDetail } from "@/lib/api/types";

import { ScheduleBulkTable } from "./schedule-bulk-table";

type Action = (scheduleIds: number[], change: { enabled?: boolean; skipHolidays?: boolean }) => Promise<ActionState>;

function schedule(id: number, suite: string, enabled: boolean, skipHolidays = false): ScheduleDetail {
  return {
    id,
    project: { id: 1, name: "결제" },
    suite: { id: id * 10, name: suite },
    environment: { id: 1, name: "dev" },
    cron: "10 9 * * 1-5",
    zone: "Asia/Seoul",
    enabled,
    skipHolidays,
    nextRunAt: enabled ? "2026-10-08T00:10:00Z" : null,
    lastTriggeredAt: null,
  };
}

const schedules = [schedule(1, "승인", true, true), schedule(2, "환불", true), schedule(3, "정산", false)];

describe("ScheduleBulkTable", () => {
  it("changes only the chosen schedules and clears the choice afterwards", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    render(<ScheduleBulkTable schedules={schedules} action={action} />);

    const toolbar = screen.getByRole("toolbar", { name: "고른 스케줄 변경" });
    expect(within(toolbar).getByRole("button", { name: "켜기" })).toBeDisabled();
    expect(within(toolbar).getByRole("button", { name: "휴일 건너뛰기 켜기" })).toBeDisabled();

    await user.click(screen.getByRole("checkbox", { name: "환불 dev 10 9 * * 1-5 선택" }));
    await user.click(screen.getByRole("checkbox", { name: "정산 dev 10 9 * * 1-5 선택" }));
    expect(toolbar).toHaveTextContent("2개 선택");
    await user.click(within(toolbar).getByRole("button", { name: "휴일 건너뛰기 켜기" }));

    await waitFor(() => expect(action).toHaveBeenCalledWith([2, 3], { skipHolidays: true }));
    await waitFor(() => expect(toolbar).toHaveTextContent("0개 선택"));

    await user.click(screen.getByRole("checkbox", { name: "모두 선택" }));
    await user.click(within(toolbar).getByRole("button", { name: "켜기" }));
    await waitFor(() => expect(action).toHaveBeenLastCalledWith([1, 2, 3], { enabled: true }));

    await user.click(screen.getByRole("checkbox", { name: "정산 dev 10 9 * * 1-5 선택" }));
    await user.click(within(toolbar).getByRole("button", { name: "휴일 건너뛰기 끄기" }));
    await waitFor(() => expect(action).toHaveBeenLastCalledWith([3], { skipHolidays: false }));
  });

  it("asks before turning schedules off", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    render(<ScheduleBulkTable schedules={schedules} action={action} />);

    await user.click(screen.getByRole("checkbox", { name: "모두 선택" }));
    await user.click(screen.getByRole("button", { name: "끄기" }));
    const dialog = screen.getByRole("alertdialog", { name: "스케줄 3개 끄기" });
    expect(dialog).toHaveTextContent("실패 알림도 오지 않습니다");

    await user.click(within(dialog).getByRole("button", { name: "취소" }));
    expect(action).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "끄기" }));
    await user.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "끄기" }));
    await waitFor(() => expect(action).toHaveBeenCalledWith([1, 2, 3], { enabled: false }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("keeps the choice and shows the error when the change fails", async () => {
    const user = userEvent.setup();
    render(<ScheduleBulkTable schedules={schedules} action={async () => ({ error: "요청을 처리하지 못했습니다" })} />);

    await user.click(screen.getByRole("checkbox", { name: "승인 dev 10 9 * * 1-5 선택" }));
    await user.click(screen.getByRole("button", { name: "켜기" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("요청을 처리하지 못했습니다");
    expect(screen.getByRole("toolbar")).toHaveTextContent("1개 선택");
  });

  it("shows the holiday skipping and state of each schedule", () => {
    render(<ScheduleBulkTable schedules={schedules} action={async () => ({})} />);

    const [, approval, , settlement] = screen.getAllByRole("row");
    expect(approval).toHaveTextContent("휴일 제외");
    expect(approval).toHaveTextContent("활성");
    expect(approval).toHaveTextContent("2026-10-08 09:10:00");
    expect(settlement).toHaveTextContent("비활성");
    expect(settlement).not.toHaveTextContent("휴일 제외");
  });
});
