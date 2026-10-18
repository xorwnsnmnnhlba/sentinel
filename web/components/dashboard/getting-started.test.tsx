import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { StartStep } from "@/lib/getting-started";

import { GettingStarted } from "./getting-started";

const steps: StartStep[] = [
  { title: "프로젝트 등록", detail: "새 프로젝트", done: true, href: null },
  { title: "환경 추가", detail: "기본 URL", done: false, href: "/projects/3" },
  { title: "스위트 추가", detail: "케이스 묶음", done: false, href: "/projects/3" },
  { title: "케이스 추가", detail: "케이스 추가 또는 문서 가져오기", done: false, href: null },
];

describe("GettingStarted", () => {
  it("counts finished steps and marks the first remaining one as current", () => {
    render(<GettingStarted steps={steps} />);

    expect(screen.getByText("4단계 중 1단계 완료")).toBeInTheDocument();
    const items = within(screen.getByRole("list", { name: "처음 시작하기 단계" })).getAllByRole("listitem");
    expect(items.map((item) => item.getAttribute("aria-current"))).toEqual([null, "step", null, null]);
  });

  it("strikes finished steps for screen readers too and hides their detail", () => {
    render(<GettingStarted steps={steps} />);

    expect(screen.getByText("(완료)")).toBeInTheDocument();
    expect(screen.queryByText("새 프로젝트")).not.toBeInTheDocument();
    expect(screen.getByText("기본 URL")).toBeInTheDocument();
  });

  it("links only to the steps that can be done now", () => {
    render(<GettingStarted steps={steps} />);

    const links = screen.getAllByRole("link");
    expect(links.map((link) => [link.textContent, link.getAttribute("href")])).toEqual([
      ["이동 : 환경 추가", "/projects/3"],
      ["이동 : 스위트 추가", "/projects/3"],
    ]);
  });
});
