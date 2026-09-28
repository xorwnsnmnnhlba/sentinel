import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";

import { CancelRunButton } from "./cancel-run-button";

describe("CancelRunButton", () => {
  it("asks before stopping and closes when stopped", async () => {
    const user = userEvent.setup();
    const action = vi.fn(async (): Promise<ActionState> => ({}));
    render(<CancelRunButton action={action} />);

    await user.click(screen.getByRole("button", { name: "중지" }));
    const dialog = screen.getByRole("alertdialog", { name: "실행 중지" });
    expect(dialog).toHaveTextContent("남은 케이스는 보내지 않습니다");
    await user.click(screen.getByRole("button", { name: "중지" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(action).toHaveBeenCalledOnce();
  });

  it("keeps running when the user changes their mind", async () => {
    const user = userEvent.setup();
    const action = vi.fn(async (): Promise<ActionState> => ({}));
    render(<CancelRunButton action={action} />);

    await user.click(screen.getByRole("button", { name: "중지" }));
    await user.click(screen.getByRole("button", { name: "계속 실행" }));

    expect(action).not.toHaveBeenCalled();
  });

  it("shows why it could not stop", async () => {
    const user = userEvent.setup();
    render(<CancelRunButton action={async () => ({ error: "이미 끝난 실행입니다" })} />);

    await user.click(screen.getByRole("button", { name: "중지" }));
    await user.click(screen.getByRole("button", { name: "중지" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 끝난 실행입니다");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
});
