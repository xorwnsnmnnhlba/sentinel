import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ImportState } from "@/lib/actions/holidays";

import { HolidayImportDialog } from "./holiday-import-dialog";

type Action = (state: ImportState, form: FormData) => Promise<ImportState>;

describe("HolidayImportDialog", () => {
  it("counts the pasted lines, registers them and shows what was skipped", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({
      created: [{ id: 2, date: "2026-12-25", name: "성탄절" }],
      skipped: [{ id: 1, date: "2027-01-01", name: "신정" }],
    }));
    render(<HolidayImportDialog action={action} />);

    await user.click(screen.getByRole("button", { name: "여러 건 붙여넣기" }));
    const lines = screen.getByLabelText("휴일 목록 (KST)");
    await user.click(lines);
    await user.paste("2026-12-25 성탄절\n2027-01-01 신정");
    expect(lines).toHaveAccessibleDescription("2건");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    expect(action.mock.calls[0][1].get("lines")).toBe("2026-12-25 성탄절\n2027-01-01 신정");
    expect(await screen.findByRole("status")).toHaveTextContent("1건 등록, 이미 등록되어 1건 건너뜀");
    expect(screen.getByRole("list", { name: "건너뛴 휴일" })).toHaveTextContent("2027-01-01신정");

    // 닫았다 다시 열면 빈 입력칸
    await user.click(screen.getByRole("button", { name: "닫기" }));
    await user.click(screen.getByRole("button", { name: "여러 건 붙여넣기" }));
    expect(screen.getByLabelText("휴일 목록 (KST)")).toHaveValue("");
  });

  it("points at the first line it cannot read while typing", async () => {
    const user = userEvent.setup();
    render(<HolidayImportDialog action={async () => ({})} />);

    await user.click(screen.getByRole("button", { name: "여러 건 붙여넣기" }));
    const lines = screen.getByLabelText("휴일 목록 (KST)");
    expect(lines).toHaveAccessibleDescription(/날짜 형식/);
    await user.click(lines);
    await user.paste("2026-12-25 성탄절\n신정");

    expect(lines).toHaveAccessibleDescription("2번째 줄: 날짜를 읽을 수 없습니다 (예: 2026-12-25 성탄절)");
  });

  it("keeps the pasted lines with the error when registering fails", async () => {
    const user = userEvent.setup();
    render(<HolidayImportDialog action={async () => ({ error: "휴일은 관리자만 등록·삭제할 수 있습니다" })} />);

    await user.click(screen.getByRole("button", { name: "여러 건 붙여넣기" }));
    await user.click(screen.getByLabelText("휴일 목록 (KST)"));
    await user.paste("2026-12-25 성탄절");
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("휴일은 관리자만 등록·삭제할 수 있습니다");
    expect(screen.getByLabelText("휴일 목록 (KST)")).toHaveValue("2026-12-25 성탄절");
  });
});
