import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { SecretsEditor } from "./secrets-editor";

function serialized(container: HTMLElement): unknown {
  const input = container.querySelector<HTMLInputElement>('input[type="hidden"][name="secrets"]');
  return JSON.parse(input!.value);
}

describe("SecretsEditor", () => {
  it("shows saved names without values and sends nothing by default", () => {
    const { container } = render(<SecretsEditor savedNames={["token", "apiKey"]} />);

    expect(screen.getByText("token")).toBeInTheDocument();
    expect(screen.getAllByText("저장됨")).toHaveLength(2);
    expect(screen.queryByLabelText(/값/)).not.toBeInTheDocument();
    expect(serialized(container)).toEqual({});
  });

  it("adds secrets with masked input", async () => {
    const user = userEvent.setup();
    const { container } = render(<SecretsEditor />);

    await user.click(screen.getByRole("button", { name: "시크릿 추가" }));
    await user.type(screen.getByLabelText("새 시크릿 1 이름"), "  token ");
    await user.type(screen.getByLabelText("새 시크릿 1 값"), "super-secret");

    expect(screen.getByLabelText("새 시크릿 1 값")).toHaveAttribute("type", "password");
    expect(serialized(container)).toEqual({ token: "super-secret" });
  });

  it("marks a saved secret for deletion and can undo it", async () => {
    const user = userEvent.setup();
    const { container } = render(<SecretsEditor savedNames={["token"]} />);

    await user.click(screen.getByRole("button", { name: "시크릿 token 삭제" }));
    expect(screen.getByText("저장 시 삭제")).toBeInTheDocument();
    expect(serialized(container)).toEqual({ token: null });

    await user.click(screen.getByRole("button", { name: "시크릿 token 삭제 취소" }));
    expect(serialized(container)).toEqual({});
  });

  it("replaces a saved secret when the same name is entered again", async () => {
    const user = userEvent.setup();
    const { container } = render(<SecretsEditor savedNames={["token"]} />);

    await user.click(screen.getByRole("button", { name: "시크릿 추가" }));
    await user.type(screen.getByLabelText("새 시크릿 1 이름"), "token");
    await user.type(screen.getByLabelText("새 시크릿 1 값"), "new-value");

    expect(serialized(container)).toEqual({ token: "new-value" });
  });

  it("drops rows without a name and removed rows", async () => {
    const user = userEvent.setup();
    const { container } = render(<SecretsEditor />);

    await user.click(screen.getByRole("button", { name: "시크릿 추가" }));
    await user.type(screen.getByLabelText("새 시크릿 1 값"), "orphan");
    await user.click(screen.getByRole("button", { name: "시크릿 추가" }));
    await user.type(screen.getByLabelText("새 시크릿 2 이름"), "token");
    await user.type(screen.getByLabelText("새 시크릿 2 값"), "value");
    expect(serialized(container)).toEqual({ token: "value" });

    await user.click(screen.getByRole("button", { name: "새 시크릿 2 삭제" }));
    expect(serialized(container)).toEqual({});
  });
});
