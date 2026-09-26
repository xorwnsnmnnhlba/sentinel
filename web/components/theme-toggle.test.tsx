import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ThemeToggle } from "./theme-toggle";

const setTheme = vi.hoisted(() => vi.fn(async () => {}));
vi.mock("@/lib/actions/theme", () => ({ setTheme }));

afterEach(() => document.documentElement.removeAttribute("data-theme"));

describe("ThemeToggle", () => {
  it("marks the current theme", () => {
    render(<ThemeToggle initial="system" />);

    expect(screen.getByRole("button", { name: "시스템 설정" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "다크" })).toHaveAttribute("aria-pressed", "false");
  });

  it("switches the page at once and saves the choice", async () => {
    const user = userEvent.setup();
    render(<ThemeToggle initial="light" />);

    await user.click(screen.getByRole("button", { name: "다크" }));

    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    expect(screen.getByRole("button", { name: "다크" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "라이트" })).toHaveAttribute("aria-pressed", "false");
    expect(setTheme).toHaveBeenCalledWith("dark");
  });
});
