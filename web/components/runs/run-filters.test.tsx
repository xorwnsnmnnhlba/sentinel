import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { EnvironmentItem, ProjectItem, SuiteItem } from "@/lib/api/types";

import { RunFilters } from "./run-filters";

const projects: ProjectItem[] = [
  { id: 1, name: "결제", description: null, webhookConfigured: false },
  { id: 2, name: "회원", description: null, webhookConfigured: false },
];

const suites: SuiteItem[] = [
  { id: 10, projectId: 1, name: "결제 스모크", description: null, caseCount: 3 },
  { id: 20, projectId: 2, name: "가입", description: null, caseCount: 1 },
];

const environments: EnvironmentItem[] = [
  { id: 100, projectId: 1, name: "dev", baseUrl: "http://dev", variables: {}, secretNames: [] },
  { id: 200, projectId: 2, name: "staging", baseUrl: "http://stg", variables: {}, secretNames: [] },
];

function renderFilters(values: Parameters<typeof RunFilters>[0]["values"] = {}) {
  return render(<RunFilters projects={projects} suites={suites} environments={environments} values={values} />);
}

describe("RunFilters", () => {
  it("submits the conditions to /runs as a query string", () => {
    renderFilters();

    const form = screen.getByRole("form", { name: "실행 이력 필터" });
    expect(form).toHaveAttribute("method", "get");
    expect(form).toHaveAttribute("action", "/runs");
    expect(screen.getByLabelText("프로젝트")).toHaveAttribute("name", "projectId");
    expect(screen.getByLabelText("스위트")).toHaveAttribute("name", "suiteId");
    expect(screen.getByLabelText("환경")).toHaveAttribute("name", "environmentId");
    expect(screen.getByLabelText("상태")).toHaveAttribute("name", "status");
    expect(screen.getByLabelText("시작일 (KST)")).toHaveAttribute("name", "from");
    expect(screen.getByLabelText("종료일 (KST)")).toHaveAttribute("name", "to");

    expect(new URLSearchParams(new FormData(form as HTMLFormElement) as never).toString()).toBe(
      "projectId=&suiteId=&environmentId=&status=&from=&to=",
    );
  });

  it("keeps the current conditions selected", () => {
    renderFilters({
      projectId: "2",
      suiteId: "20",
      environmentId: "200",
      status: "RUNNING",
      from: "2026-09-01",
      to: "2026-09-22",
    });

    expect(screen.getByLabelText("프로젝트")).toHaveValue("2");
    expect(screen.getByLabelText("스위트")).toHaveValue("20");
    expect(screen.getByLabelText("환경")).toHaveValue("200");
    expect(screen.getByLabelText("상태")).toHaveValue("RUNNING");
    expect(screen.getByLabelText("시작일 (KST)")).toHaveValue("2026-09-01");
    expect(screen.getByLabelText("종료일 (KST)")).toHaveValue("2026-09-22");
  });

  it("groups suites and environments by project", () => {
    renderFilters();

    const suiteGroups = within(screen.getByLabelText("스위트")).getAllByRole("group");
    expect(suiteGroups.map((group) => group.getAttribute("label"))).toEqual(["결제", "회원"]);
    expect(within(suiteGroups[0]).getByRole("option", { name: "결제 스모크" })).toHaveValue("10");
    expect(within(suiteGroups[1]).getByRole("option", { name: "가입" })).toHaveValue("20");

    const environmentGroups = within(screen.getByLabelText("환경")).getAllByRole("group");
    expect(within(environmentGroups[0]).getByRole("option", { name: "dev" })).toHaveValue("100");
    expect(within(environmentGroups[1]).getByRole("option", { name: "staging" })).toHaveValue("200");
  });

  it("narrows to runs with failures when checked", () => {
    renderFilters({ failing: "true" });

    const failing = screen.getByRole("checkbox", { name: "실패·오류만" });
    expect(failing).toBeChecked();
    const form = screen.getByRole("form", { name: "실행 이력 필터" }) as HTMLFormElement;
    expect(new FormData(form).get("failing")).toBe("true");
    // 조건이 있으므로 초기화 가능
    expect(screen.getByRole("link", { name: "초기화" })).toBeInTheDocument();
  });

  it("offers a reset link only while a condition is set", () => {
    const { rerender } = renderFilters();
    expect(screen.queryByRole("link", { name: "초기화" })).not.toBeInTheDocument();

    rerender(
      <RunFilters projects={projects} suites={suites} environments={environments} values={{ status: "COMPLETED" }} />,
    );
    expect(screen.getByRole("link", { name: "초기화" })).toHaveAttribute("href", "/runs");
  });
});
