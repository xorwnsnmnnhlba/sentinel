import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { IssueState } from "@/lib/actions/access-tokens";

import { IssueTokenDialog } from "./issue-token-dialog";

type Action = (state: IssueState, form: FormData) => Promise<IssueState>;

describe("IssueTokenDialog", () => {
  it("issues with the chosen name and validity, then shows the token only once", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({ token: "snt_issued", expiresAt: "2026-12-29T00:00:00Z" }));
    render(<IssueTokenDialog action={action} />);

    await user.click(screen.getByRole("button", { name: "토큰 발급" }));
    expect(screen.getByLabelText("유효 기간")).toHaveValue("90");
    await user.type(screen.getByLabelText("이름"), "GitHub Actions");
    await user.selectOptions(screen.getByLabelText("유효 기간"), "365");
    await user.click(screen.getByRole("button", { name: "발급" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
    const sent = action.mock.calls[0][1];
    expect(sent.get("name")).toBe("GitHub Actions");
    expect(sent.get("validityDays")).toBe("365");
    expect(await screen.findByLabelText("발급된 토큰")).toHaveValue("snt_issued");
    expect(screen.getByText(/지금만 확인할 수 있습니다/)).toHaveTextContent("2026-12-29 09:00:00");

    // 닫았다 다시 열면 새 발급 폼 (이전 토큰은 다시 보이지 않음)
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "토큰 발급" }));
    expect(screen.queryByLabelText("발급된 토큰")).not.toBeInTheDocument();
    expect(screen.getByLabelText("이름")).toHaveValue("");
  });

  it("keeps the form with the error when issuing fails", async () => {
    const user = userEvent.setup();
    render(<IssueTokenDialog action={async () => ({ error: "토큰 이름(용도)을 입력해 주세요" })} />);

    await user.click(screen.getByRole("button", { name: "토큰 발급" }));
    await user.type(screen.getByLabelText("이름"), "x");
    await user.click(screen.getByRole("button", { name: "발급" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("토큰 이름(용도)을 입력해 주세요");
    expect(screen.getByLabelText("이름")).toHaveValue("x");
  });
});
