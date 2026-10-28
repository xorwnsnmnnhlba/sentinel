import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ActionState } from "@/lib/actions/catalog";

import { ApproveSchemaButton } from "./approve-schema-button";

describe("ApproveSchemaButton", () => {
  it("asks before approving and closes when approved", async () => {
    const user = userEvent.setup();
    const action = vi.fn(async (): Promise<ActionState> => ({}));
    render(<ApproveSchemaButton action={action} />);

    await user.click(screen.getByRole("button", { name: "기준선에 반영" }));
    const dialog = screen.getByRole("alertdialog", { name: "기준선에 반영" });
    expect(dialog).toHaveTextContent("다음 실행부터 같은 변화를 보고하지 않습니다");
    expect(dialog).toHaveTextContent("되돌릴 수 없습니다");
    await user.click(screen.getByRole("button", { name: "반영" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(action).toHaveBeenCalledOnce();
  });

  it("leaves the baseline alone when the user changes their mind", async () => {
    const user = userEvent.setup();
    const action = vi.fn(async (): Promise<ActionState> => ({}));
    render(<ApproveSchemaButton action={action} />);

    await user.click(screen.getByRole("button", { name: "기준선에 반영" }));
    await user.click(screen.getByRole("button", { name: "취소" }));

    expect(action).not.toHaveBeenCalled();
  });

  it("shows why it could not approve", async () => {
    const user = userEvent.setup();
    render(<ApproveSchemaButton action={async () => ({ error: "이미 기준선에 반영된 결과입니다" })} />);

    await user.click(screen.getByRole("button", { name: "기준선에 반영" }));
    await user.click(screen.getByRole("button", { name: "반영" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 기준선에 반영된 결과입니다");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
});
