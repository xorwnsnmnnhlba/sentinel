import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PassRateMeter } from "./pass-rate-meter";
import { StatTile } from "./stat-tile";

describe("StatTile", () => {
  it("states whether the change is good or bad in words, not only color", () => {
    render(
      <StatTile
        label="실패·오류가 있는 실행"
        value="4회"
        delta={{ direction: "up", tone: "bad", text: "+2회" }}
        comparedTo="이전 7일 대비"
      />,
    );

    expect(screen.getByText("4회")).toBeInTheDocument();
    const change = screen.getByText(/\+2회/);
    expect(change).toHaveTextContent("+2회 (나빠짐)");
    expect(change.parentElement).toHaveClass("text-(--delta-bad)");
    expect(screen.getByText("이전 7일 대비")).toBeInTheDocument();
  });

  it("keeps neutral changes muted and says when there is nothing to compare", () => {
    const { rerender } = render(
      <StatTile label="실행" value="12회" delta={{ direction: "down", tone: "neutral", text: "−3회" }} comparedTo="" />,
    );
    expect(screen.getByText("−3회").parentElement).toHaveClass("text-muted-foreground");

    rerender(<StatTile label="실행" value="12회" delta={null} comparedTo="이전 7일 대비" />);
    expect(screen.getByText("비교할 이전 기간 결과 없음")).toBeInTheDocument();
  });
});

describe("PassRateMeter", () => {
  it("exposes the rate as a meter and as text", () => {
    render(<PassRateMeter rate={0.875} label="결제 통과율" />);

    const meter = screen.getByRole("meter", { name: "결제 통과율" });
    expect(meter).toHaveAttribute("aria-valuenow", "87.5");
    expect(screen.getByText("87.5%")).toBeInTheDocument();
  });

  it("shows a dash without results", () => {
    render(<PassRateMeter rate={null} label="결제 통과율" />);

    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
    expect(screen.getByText("–")).toBeInTheDocument();
  });
});
