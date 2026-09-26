import { screen } from "@testing-library/react";
import type userEvent from "@testing-library/user-event";

/** "⋯" 메뉴 열기. jsdom에서 Radix 목록은 포인터로 두 번째부터 열리지 않아 키보드 사용 (README "테스트") */
export async function openMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  screen.getByRole("button", { name }).focus();
  await user.keyboard("{Enter}");
}
