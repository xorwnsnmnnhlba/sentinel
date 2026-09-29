import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";

import { RerunFailedForm } from "./rerun-failed-form";

describe("RerunFailedForm", () => {
  it("starts the rerun", async () => {
    const user = userEvent.setup();
    const action = vi.fn<() => Promise<ActionState>>(async () => undefined);
    render(<RerunFailedForm action={action} />);

    await user.click(screen.getByRole("button", { name: "실패만 다시 실행" }));

    await waitFor(() => expect(action).toHaveBeenCalledOnce());
  });

  it("shows why the run cannot be rerun", async () => {
    const user = userEvent.setup();
    render(<RerunFailedForm action={async () => ({ error: "다시 실행할 실패 케이스가 없습니다" })} />);

    await user.click(screen.getByRole("button", { name: "실패만 다시 실행" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("다시 실행할 실패 케이스가 없습니다");
  });

  it("disables the button while starting", async () => {
    const user = userEvent.setup();
    render(<RerunFailedForm action={() => new Promise<ActionState>(() => {})} />);

    await user.click(screen.getByRole("button", { name: "실패만 다시 실행" }));

    expect(await screen.findByRole("button", { name: "다시 실행 중…" })).toBeDisabled();
  });
});
