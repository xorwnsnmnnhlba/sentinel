import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { ProjectFields } from "@/components/catalog/entity-fields";
import { Button } from "@/components/ui/button";
import type { ActionState } from "@/lib/actions/catalog";

import { FormDialog } from "./form-dialog";

type Action = (state: ActionState, form: FormData) => Promise<ActionState>;

function renderDialog(action: Action) {
  render(
    <FormDialog trigger={<Button>새 프로젝트</Button>} title="새 프로젝트" submitLabel="등록" action={action}>
      <ProjectFields />
    </FormDialog>,
  );
}

describe("FormDialog", () => {
  it("submits form values and closes on success", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action);

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.type(screen.getByLabelText("이름"), "billing");
    await user.type(screen.getByLabelText(/설명/), "결제");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const submitted = action.mock.calls[0][1];
    expect(Object.fromEntries(submitted)).toEqual({ name: "billing", description: "결제" });
  });

  it("keeps the dialog and typed values when the action fails", async () => {
    const user = userEvent.setup();
    renderDialog(async () => ({ error: "이미 사용 중인 이름입니다" }));

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.type(screen.getByLabelText("이름"), "billing");
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 사용 중인 이름입니다");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("이름")).toHaveValue("billing");
  });

  it("shows pending state and blocks double submit", async () => {
    const user = userEvent.setup();
    let finish: (state: ActionState) => void = () => {};
    const action = vi.fn<Action>(() => new Promise<ActionState>((resolve) => (finish = resolve)));
    renderDialog(action);

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.type(screen.getByLabelText("이름"), "billing");
    await user.click(screen.getByRole("button", { name: "등록" }));

    const pending = await screen.findByRole("button", { name: "저장 중…" });
    expect(pending).toBeDisabled();
    finish({});
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(action).toHaveBeenCalledOnce();
  });

  it("does not submit without the required name", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action);

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(action).not.toHaveBeenCalled();
    expect(screen.getByLabelText("이름")).toBeInvalid();
  });

  it("resets values after closing without saving", async () => {
    const user = userEvent.setup();
    renderDialog(async () => ({}));

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.type(screen.getByLabelText("이름"), "draft");
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));

    expect(screen.getByLabelText("이름")).toHaveValue("");
  });

  it("closes without submitting when cancelled", async () => {
    const user = userEvent.setup();
    const action = vi.fn<Action>(async () => ({}));
    renderDialog(action);

    await user.click(screen.getByRole("button", { name: "새 프로젝트" }));
    await user.type(screen.getByLabelText("이름"), "billing");
    await user.click(screen.getByRole("button", { name: "취소" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(action).not.toHaveBeenCalled();
  });
});
