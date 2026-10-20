import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { LinkState } from "@/lib/actions/users";

import { InviteDialog } from "./invite-dialog";

const invite = vi.fn<(baseUrl: string, state: LinkState, form: FormData) => Promise<LinkState>>();
vi.mock("@/lib/actions/users", () => ({ invite: (...args: Parameters<typeof invite>) => invite(...args) }));

const BASE_URL = "https://sentinel.example.com";

describe("InviteDialog", () => {
  beforeEach(() => invite.mockReset());

  it("invites as a member unless another role is chosen, then shows the link to pass on", async () => {
    const user = userEvent.setup();
    invite.mockResolvedValue({ link: `${BASE_URL}/invites/token-1`, expiresAt: "2026-10-26T00:00:00Z" });
    render(<InviteDialog baseUrl={BASE_URL} />);

    await user.click(screen.getByRole("button", { name: "사용자 초대" }));
    expect(screen.getByLabelText("역할")).toHaveValue("MEMBER");
    await user.type(screen.getByLabelText("이메일"), "new@sentinel.test");
    await user.selectOptions(screen.getByLabelText("역할"), "ADMIN");
    await user.click(screen.getByRole("button", { name: "초대 링크 발급" }));

    await waitFor(() => expect(invite).toHaveBeenCalledOnce());
    const [baseUrl, , form] = invite.mock.calls[0];
    expect(baseUrl).toBe(BASE_URL);
    expect(form.get("email")).toBe("new@sentinel.test");
    expect(form.get("role")).toBe("ADMIN");
    expect(await screen.findByLabelText("발급된 링크")).toHaveValue(`${BASE_URL}/invites/token-1`);
    // 같은 창에서 다시 누르면 새 링크 (이전 링크는 그대로 유효)
    expect(screen.getByRole("button", { name: "다시 발급" })).toBeEnabled();
  });

  it("keeps the entered email with the reason when issuing fails", async () => {
    const user = userEvent.setup();
    invite.mockResolvedValue({ error: "이미 가입한 이메일입니다" });
    render(<InviteDialog baseUrl={BASE_URL} />);

    await user.click(screen.getByRole("button", { name: "사용자 초대" }));
    await user.type(screen.getByLabelText("이메일"), "taken@sentinel.test");
    await user.click(screen.getByRole("button", { name: "초대 링크 발급" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 가입한 이메일입니다");
    expect(screen.getByLabelText("이메일")).toHaveValue("taken@sentinel.test");
    expect(screen.queryByLabelText("발급된 링크")).not.toBeInTheDocument();
  });
});
