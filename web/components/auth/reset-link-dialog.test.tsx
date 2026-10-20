import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ActionMenu } from "@/components/catalog/action-menu";
import type { LinkState } from "@/lib/actions/users";
import { openMenu } from "@/test/menu";

import { ResetLinkDialog } from "./reset-link-dialog";

const createPasswordReset = vi.fn<(baseUrl: string, userId: number) => Promise<LinkState>>();
vi.mock("@/lib/actions/users", () => ({
  createPasswordReset: (...args: Parameters<typeof createPasswordReset>) => createPasswordReset(...args),
}));

const BASE_URL = "https://sentinel.example.com";

function renderMenu() {
  render(
    <ActionMenu label="member@sentinel.test 작업" items={[{ id: "reset", label: "비밀번호 재설정 링크", icon: "key" }]}>
      <ResetLinkDialog menuId="reset" userId={7} email="member@sentinel.test" baseUrl={BASE_URL} />
    </ActionMenu>,
  );
}

async function openReset(user: ReturnType<typeof userEvent.setup>) {
  await openMenu(user, "member@sentinel.test 작업");
  await user.click(await screen.findByRole("menuitem", { name: "비밀번호 재설정 링크" }));
}

describe("ResetLinkDialog", () => {
  beforeEach(() => createPasswordReset.mockReset());

  it("issues a link for that user on request and shows it in place of the button", async () => {
    const user = userEvent.setup();
    createPasswordReset.mockResolvedValue({
      link: `${BASE_URL}/password-resets/token-1`,
      expiresAt: "2026-10-19T02:00:00Z",
    });
    renderMenu();

    await openReset(user);
    expect(screen.getByRole("dialog")).toHaveTextContent("member@sentinel.test 계정의 재설정 링크를 만듭니다");
    // 열기만 해서는 발급하지 않음 (발급하면 이전 링크와 별개로 새 링크가 생김)
    expect(createPasswordReset).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "링크 발급" }));

    expect(await screen.findByLabelText("발급된 링크")).toHaveValue(`${BASE_URL}/password-resets/token-1`);
    expect(createPasswordReset).toHaveBeenCalledWith(BASE_URL, 7);
    expect(screen.queryByRole("button", { name: "링크 발급" })).not.toBeInTheDocument();
  });

  it("does not show the issued link again after closing", async () => {
    const user = userEvent.setup();
    createPasswordReset.mockResolvedValue({ link: `${BASE_URL}/password-resets/token-1` });
    renderMenu();

    await openReset(user);
    await user.click(screen.getByRole("button", { name: "링크 발급" }));
    await screen.findByLabelText("발급된 링크");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await openReset(user);
    expect(screen.queryByLabelText("발급된 링크")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "링크 발급" })).toBeEnabled();
  });

  it("shows why issuing failed and lets the admin try again", async () => {
    const user = userEvent.setup();
    createPasswordReset.mockResolvedValue({ error: "사용자를 찾을 수 없습니다" });
    renderMenu();

    await openReset(user);
    await user.click(screen.getByRole("button", { name: "링크 발급" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("사용자를 찾을 수 없습니다");
    expect(screen.getByRole("button", { name: "링크 발급" })).toBeEnabled();
  });
});
