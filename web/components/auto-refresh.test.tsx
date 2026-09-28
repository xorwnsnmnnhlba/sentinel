import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AutoRefresh } from "./auto-refresh";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

describe("AutoRefresh", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-28T00:00:00Z"));
    refresh.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("reloads the page at the given interval until it is removed", () => {
    const { unmount } = render(<AutoRefresh intervalMs={3000} />);

    act(() => vi.advanceTimersByTime(9000));
    expect(refresh).toHaveBeenCalledTimes(3);

    unmount();
    act(() => vi.advanceTimersByTime(9000));
    expect(refresh).toHaveBeenCalledTimes(3);
  });

  it("stops once the deadline passes", () => {
    render(<AutoRefresh intervalMs={1000} until="2026-09-28T00:00:02.500Z" />);

    act(() => vi.advanceTimersByTime(10_000));

    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("does not start after the deadline", () => {
    render(<AutoRefresh intervalMs={1000} until="2026-09-27T23:59:59Z" />);

    act(() => vi.advanceTimersByTime(5000));

    expect(refresh).not.toHaveBeenCalled();
  });
});
