import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { RetryBadge, UnstableBadge } from "./run-badges";

describe("RetryBadge", () => {
  it("shows how many times the case was sent again", () => {
    render(<RetryBadge retries={2} />);

    expect(screen.getByText("재시도 2회")).toBeInTheDocument();
  });

  it("shows nothing when the first attempt got a response", () => {
    const { container } = render(<RetryBadge retries={0} />);

    expect(container).toBeEmptyDOMElement();
  });
});

describe("UnstableBadge", () => {
  it("reads out why the case is unstable", () => {
    render(<UnstableBadge detail="최근 7일 dev: 실패 구간 2회" />);

    expect(screen.getByText("불안정").closest("[title]")).toHaveAttribute("title", "최근 7일 dev: 실패 구간 2회");
    expect(screen.getByText("(최근 7일 dev: 실패 구간 2회)", { exact: false })).toBeInTheDocument();
  });
});
