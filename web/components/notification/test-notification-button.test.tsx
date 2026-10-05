import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import type { TestNotificationState } from "@/lib/actions/catalog";

import { TestNotificationButton } from "./test-notification-button";

describe("TestNotificationButton", () => {
  it("shows that the test notification was sent", async () => {
    const user = userEvent.setup();
    render(
      <TestNotificationButton action={async () => ({ delivery: { delivered: true, statusCode: 200, error: null } })} />,
    );

    await user.click(screen.getByRole("button", { name: "테스트 알림 보내기" }));

    expect(await screen.findByRole("status")).toHaveTextContent("테스트 알림을 보냈습니다 (HTTP 200)");
    expect(screen.getByRole("status")).not.toHaveClass("text-destructive");
  });

  it("shows why the channel did not get it", async () => {
    const user = userEvent.setup();
    render(
      <TestNotificationButton
        action={async () => ({ delivery: { delivered: false, statusCode: null, error: "CONNECTION_FAILED" } })}
      />,
    );

    await user.click(screen.getByRole("button", { name: "테스트 알림 보내기" }));

    expect(await screen.findByText("테스트 알림을 보내지 못했습니다: 연결하지 못했습니다")).toHaveClass(
      "text-destructive",
    );
  });

  it("shows action errors and disables the button while sending", async () => {
    const user = userEvent.setup();
    let finish: (state: TestNotificationState) => void = () => {};
    render(
      <TestNotificationButton action={() => new Promise<TestNotificationState>((resolve) => (finish = resolve))} />,
    );

    await user.click(screen.getByRole("button", { name: "테스트 알림 보내기" }));
    // 전송 중 표시는 다음 렌더에서 나타나므로 기다림 (전체 테스트 부하에서 바로 확인하면 간헐 실패)
    expect(await screen.findByRole("button", { name: "보내는 중…" })).toBeDisabled();

    finish({ error: "지정된 Webhook이 없습니다" });
    expect(await screen.findByText("지정된 Webhook이 없습니다")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "테스트 알림 보내기" })).toBeEnabled();
  });
});
