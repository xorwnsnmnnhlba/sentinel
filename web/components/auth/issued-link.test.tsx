import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { IssuedLinkBox } from "./issued-link";

const LINK = "https://sentinel.example.com/invites/token-1";

describe("IssuedLinkBox", () => {
  it("shows the link read-only with its expiry in KST and says it is shown only now", () => {
    render(<IssuedLinkBox link={LINK} expiresAt="2026-10-26T00:00:00Z" />);

    const field = screen.getByLabelText("발급된 링크");
    expect(field).toHaveValue(LINK);
    expect(field).toHaveAttribute("readonly");
    expect(screen.getByText(/링크는 지금만 확인할 수 있습니다/)).toHaveTextContent("만료 2026-10-26 09:00:00 KST");
  });

  it("leaves out the expiry when it is unknown", () => {
    render(<IssuedLinkBox link={LINK} />);

    expect(screen.getByText(/링크는 지금만 확인할 수 있습니다/)).not.toHaveTextContent("만료");
  });

  it("copies the link and marks the button as copied", async () => {
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, "writeText");
    render(<IssuedLinkBox link={LINK} />);

    await user.click(screen.getByRole("button", { name: "링크 복사" }));

    expect(writeText).toHaveBeenCalledWith(LINK);
    expect(screen.getByRole("button", { name: "링크 복사" }).querySelector(".lucide-check")).not.toBeNull();
  });

  it("keeps the copy icon when the clipboard is not allowed, so the link is copied from the field", async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, "writeText").mockRejectedValue(new Error("denied"));
    render(<IssuedLinkBox link={LINK} />);

    await user.click(screen.getByRole("button", { name: "링크 복사" }));

    const button = screen.getByRole("button", { name: "링크 복사" });
    expect(button.querySelector(".lucide-check")).toBeNull();
    expect(button.querySelector(".lucide-copy")).not.toBeNull();
  });
});
