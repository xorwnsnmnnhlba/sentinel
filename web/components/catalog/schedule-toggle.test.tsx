import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { setScheduleEnabled } from "@/lib/actions/catalog";

import { ScheduleToggle } from "./schedule-toggle";

// 서버 전용 모듈(Server Action)은 테스트용 함수로 대체
vi.mock("@/lib/actions/catalog", () => ({ setScheduleEnabled: vi.fn(async () => ({})) }));

describe("ScheduleToggle", () => {
  it("turns an active schedule off", async () => {
    const user = userEvent.setup();
    render(<ScheduleToggle scheduleId={3} enabled label="스케줄 10 9 * * 1-5 활성" />);

    const toggle = screen.getByRole("switch", { name: "스케줄 10 9 * * 1-5 활성" });
    expect(toggle).toBeChecked();
    expect(screen.getByText("활성")).toBeInTheDocument();

    await user.click(toggle);

    await waitFor(() => expect(setScheduleEnabled).toHaveBeenCalledWith(3, false));
  });

  it("turns an inactive schedule on", async () => {
    const user = userEvent.setup();
    render(<ScheduleToggle scheduleId={3} enabled={false} label="스케줄 활성" />);

    expect(screen.getByText("비활성")).toBeInTheDocument();
    await user.click(screen.getByRole("switch", { name: "스케줄 활성" }));

    await waitFor(() => expect(setScheduleEnabled).toHaveBeenCalledWith(3, true));
  });

  it("shows the error when the change fails", async () => {
    const user = userEvent.setup();
    vi.mocked(setScheduleEnabled).mockResolvedValueOnce({ error: "대상을 찾을 수 없습니다" });
    render(<ScheduleToggle scheduleId={3} enabled={false} label="스케줄 활성" />);

    await user.click(screen.getByRole("switch", { name: "스케줄 활성" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("대상을 찾을 수 없습니다");
  });
});
