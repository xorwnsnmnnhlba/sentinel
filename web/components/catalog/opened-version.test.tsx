import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { OpenedVersion } from "./opened-version";

function submitted(container: HTMLElement): string | null {
  return container.querySelector<HTMLInputElement>('input[name="version"]')!.value;
}

describe("OpenedVersion", () => {
  it("keeps the version from when the form opened even after the page re-renders with a newer one", () => {
    // 충돌 뒤 refresh로 새 version이 내려와도 다시 누른 저장이 옛 내용을 새 version으로 보내지 않도록
    const { container, rerender } = render(<OpenedVersion version={3} />);
    rerender(<OpenedVersion version={4} />);

    expect(submitted(container)).toBe("3");
  });

  it("starts from the latest version when the form opens again", () => {
    const { container, unmount } = render(<OpenedVersion version={3} />);
    unmount();
    const reopened = render(<OpenedVersion version={4} />);

    expect(container.innerHTML).toBe("");
    expect(submitted(reopened.container)).toBe("4");
  });
});
