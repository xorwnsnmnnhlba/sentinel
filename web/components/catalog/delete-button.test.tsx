import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";

import { DeleteButton } from "./delete-button";

function renderButton(action: () => Promise<ActionState>, label?: string) {
  render(
    <DeleteButton
      title='스위트 "smoke" 삭제'
      description="케이스 4건과 스케줄을 함께 삭제합니다."
      action={action}
      label={label}
    />,
  );
}

describe("DeleteButton", () => {
  it("asks for confirmation before deleting", async () => {
    const user = userEvent.setup();
    const action = vi.fn<() => Promise<ActionState>>(async () => ({}));
    renderButton(action);

    await user.click(screen.getByRole("button", { name: '스위트 "smoke" 삭제' }));

    expect(screen.getByRole("alertdialog")).toHaveTextContent("케이스 4건과 스케줄을 함께 삭제합니다.");
    expect(action).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "삭제" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(action).toHaveBeenCalledOnce();
  });

  it("does nothing when cancelled", async () => {
    const user = userEvent.setup();
    const action = vi.fn<() => Promise<ActionState>>(async () => ({}));
    renderButton(action, "삭제");

    await user.click(screen.getByRole("button", { name: "삭제" }));
    await user.click(screen.getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(action).not.toHaveBeenCalled();
  });

  it("shows the error and stays open when deletion fails, clearing it on reopen", async () => {
    const user = userEvent.setup();
    renderButton(async () => ({ error: "대상을 찾을 수 없습니다" }));

    await user.click(screen.getByRole("button", { name: '스위트 "smoke" 삭제' }));
    await user.click(screen.getByRole("button", { name: "삭제" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("대상을 찾을 수 없습니다");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "취소" }));
    await user.click(screen.getByRole("button", { name: '스위트 "smoke" 삭제' }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
