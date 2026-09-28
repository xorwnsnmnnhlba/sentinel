import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { REFRESH_INTERVAL_MS, RunProgress } from "./run-progress";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

describe("RunProgress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    refresh.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows how many planned cases are done", () => {
    render(<RunProgress done={5} planned={12} />);

    expect(screen.getByRole("status")).toHaveTextContent("실행 중 · 12건 중 5건 완료");
  });

  it("shows only the finished count for runs without a plan", () => {
    render(<RunProgress done={3} planned={null} />);

    expect(screen.getByRole("status")).toHaveTextContent("실행 중 · 3건 완료");
  });

  it("reloads the page periodically until it is removed", () => {
    const { unmount } = render(<RunProgress done={0} planned={2} />);

    act(() => vi.advanceTimersByTime(REFRESH_INTERVAL_MS * 2));
    expect(refresh).toHaveBeenCalledTimes(2);

    unmount();
    act(() => vi.advanceTimersByTime(REFRESH_INTERVAL_MS * 3));
    expect(refresh).toHaveBeenCalledTimes(2);
  });
});
