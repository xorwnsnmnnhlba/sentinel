import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { VariablesEditor } from "./variables-editor";

function serialized(container: HTMLElement): unknown {
  const input = container.querySelector<HTMLInputElement>('input[type="hidden"][name="variables"]');
  return JSON.parse(input!.value);
}

describe("VariablesEditor", () => {
  it("starts with existing variables", () => {
    const { container } = render(<VariablesEditor defaultValue={{ userId: "42", locale: "ko-KR" }} />);

    expect(screen.getByLabelText("변수 1 이름")).toHaveValue("userId");
    expect(serialized(container)).toEqual({ userId: "42", locale: "ko-KR" });
  });

  it("adds, edits and removes rows", async () => {
    const user = userEvent.setup();
    const { container } = render(<VariablesEditor />);

    await user.type(screen.getByLabelText("변수 1 이름"), "userId");
    await user.type(screen.getByLabelText("변수 1 값"), "42");
    await user.click(screen.getByRole("button", { name: "변수 추가" }));
    await user.type(screen.getByLabelText("변수 2 이름"), "token");
    await user.type(screen.getByLabelText("변수 2 값"), "t");
    expect(serialized(container)).toEqual({ userId: "42", token: "t" });

    await user.click(screen.getByRole("button", { name: "변수 1 삭제" }));
    expect(serialized(container)).toEqual({ token: "t" });
  });

  it("drops rows without a name and trims names", async () => {
    const user = userEvent.setup();
    const { container } = render(<VariablesEditor />);

    await user.type(screen.getByLabelText("변수 1 값"), "orphan");
    await user.click(screen.getByRole("button", { name: "변수 추가" }));
    await user.type(screen.getByLabelText("변수 2 이름"), "  userId ");
    await user.type(screen.getByLabelText("변수 2 값"), "42");

    expect(serialized(container)).toEqual({ userId: "42" });
  });

  it("keeps the last value for duplicate names", async () => {
    const user = userEvent.setup();
    const { container } = render(<VariablesEditor defaultValue={{ userId: "1" }} />);

    await user.click(screen.getByRole("button", { name: "변수 추가" }));
    await user.type(screen.getByLabelText("변수 2 이름"), "userId");
    await user.type(screen.getByLabelText("변수 2 값"), "2");

    expect(serialized(container)).toEqual({ userId: "2" });
  });
});
