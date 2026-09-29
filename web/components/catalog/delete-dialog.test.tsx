import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";
import { openMenu } from "@/test/menu";

import { ActionMenu } from "./action-menu";
import { DeleteDialog } from "./delete-dialog";
import { EDIT_DELETE } from "./menu-items";

function renderMenu(action: () => Promise<ActionState>) {
  render(
    <ActionMenu label="스위트 smoke 작업" items={EDIT_DELETE}>
      <DeleteDialog
        menuId="delete"
        title='스위트 "smoke" 삭제'
        description="케이스 4건과 스케줄을 함께 삭제합니다."
        action={action}
      />
    </ActionMenu>,
  );
}

async function openDelete(user: ReturnType<typeof userEvent.setup>) {
  await openMenu(user, "스위트 smoke 작업");
  await user.click(await screen.findByRole("menuitem", { name: "삭제" }));
}

describe("DeleteDialog", () => {
  it("names the confirm button after what deleting means", async () => {
    const user = userEvent.setup();
    let finish: (state: ActionState) => void = () => {};
    const action = vi.fn<() => Promise<ActionState>>(() => new Promise<ActionState>((resolve) => (finish = resolve)));
    render(
      <ActionMenu label="dev 점검 시간 작업" items={EDIT_DELETE}>
        <DeleteDialog
          menuId="delete"
          title="dev 점검 지금 종료"
          description="다음 스케줄부터 다시 실행합니다."
          confirmLabel="지금 종료"
          action={action}
        />
      </ActionMenu>,
    );

    await openMenu(user, "dev 점검 시간 작업");
    await user.click(await screen.findByRole("menuitem", { name: "삭제" }));
    await user.click(screen.getByRole("button", { name: "지금 종료" }));

    expect(await screen.findByRole("button", { name: "지금 종료 중…" })).toBeDisabled();
    finish({});
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
  });

  it("asks for confirmation from the menu before deleting", async () => {
    const user = userEvent.setup();
    const action = vi.fn<() => Promise<ActionState>>(async () => ({}));
    renderMenu(action);

    await openDelete(user);

    expect(screen.getByRole("alertdialog")).toHaveTextContent("케이스 4건과 스케줄을 함께 삭제합니다.");
    expect(action).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "삭제" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(action).toHaveBeenCalledOnce();
  });

  it("does nothing when cancelled and returns focus to the menu button", async () => {
    const user = userEvent.setup();
    const action = vi.fn<() => Promise<ActionState>>(async () => ({}));
    renderMenu(action);

    await openDelete(user);
    await user.click(screen.getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(action).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole("button", { name: "스위트 smoke 작업" })).toHaveFocus());
  });

  it("shows the error and stays open when deletion fails, clearing it on reopen", async () => {
    const user = userEvent.setup();
    renderMenu(async () => ({ error: "대상을 찾을 수 없습니다" }));

    await openDelete(user);
    await user.click(screen.getByRole("button", { name: "삭제" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("대상을 찾을 수 없습니다");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "취소" }));
    await openDelete(user);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
