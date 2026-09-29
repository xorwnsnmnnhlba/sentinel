import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { RetryBadge } from "./run-badges";

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
