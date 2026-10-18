import { describe, expect, it } from "vitest";

import type { EnvironmentItem, ProjectItem, ScheduleDetail, SuiteItem } from "@/lib/api/types";

import { showGettingStarted, type StartState, startSteps } from "./getting-started";

const project = (id: number, webhookConfigured = false): ProjectItem => ({
  id,
  name: `p${id}`,
  description: null,
  webhookConfigured,
});
const environment = (id: number, projectId: number): EnvironmentItem => ({
  id,
  project: { id: projectId, name: `p${projectId}` },
  name: "dev",
  baseUrl: "https://dev.example.com",
  variables: {},
  secretNames: [],
  version: 0,
});
const suite = (id: number, projectId: number, caseCount: number): SuiteItem => ({
  id,
  project: { id: projectId, name: `p${projectId}` },
  name: `s${id}`,
  description: null,
  caseCount,
});
const schedule: ScheduleDetail = {
  id: 1,
  project: { id: 1, name: "p1" },
  suite: { id: 1, name: "s1" },
  environment: { id: 1, name: "dev" },
  cron: "10 9 * * 1-5",
  zone: "Asia/Seoul",
  enabled: true,
  skipHolidays: false,
  nextRunAt: null,
  lastTriggeredAt: null,
};

const empty: StartState = { projects: [], environments: [], suites: [], schedules: [], defaultChannel: false };

/** 단계별 [끝남, 갈 곳] */
const summary = (state: StartState) => startSteps(state).map((step) => [step.title, step.done, step.href]);

describe("getting started", () => {
  it("starts from registering a project, with nothing else reachable yet", () => {
    expect(summary(empty)).toEqual([
      ["프로젝트 등록", false, "/projects"],
      ["환경 추가", false, null],
      ["스위트 추가", false, null],
      ["케이스 추가", false, null],
      ["알림 채널 확인", false, null],
      ["스케줄 추가", false, null],
    ]);
  });

  it("points the next steps at the project once it exists", () => {
    expect(summary({ ...empty, projects: [project(3)] })).toEqual([
      ["프로젝트 등록", true, null],
      ["환경 추가", false, "/projects/3"],
      ["스위트 추가", false, "/projects/3"],
      ["케이스 추가", false, null],
      ["알림 채널 확인", false, "/projects/3"],
      ["스케줄 추가", false, null],
    ]);
  });

  it("continues in the project that already has an environment and the suite that has cases", () => {
    const state: StartState = {
      ...empty,
      projects: [project(1), project(2)],
      environments: [environment(5, 2)],
      suites: [suite(7, 1, 3), suite(8, 2, 0), suite(9, 2, 2)],
    };

    expect(summary(state)).toEqual([
      ["프로젝트 등록", true, null],
      ["환경 추가", true, null],
      ["스위트 추가", true, null],
      ["케이스 추가", true, null],
      ["알림 채널 확인", false, "/projects/2"],
      // 케이스와 환경이 같은 프로젝트에 있어야 스케줄을 만들 수 있음
      ["스케줄 추가", false, "/suites/9"],
    ]);
  });

  it("opens the first suite of the project for the first case", () => {
    const state: StartState = { ...empty, projects: [project(1)], suites: [suite(4, 1, 0)] };

    expect(startSteps(state)[3]).toMatchObject({ done: false, href: "/suites/4" });
  });

  it("waits for a project that has both cases and an environment before offering a schedule", () => {
    // 케이스는 프로젝트 1, 환경은 프로젝트 2에만
    const state: StartState = {
      ...empty,
      projects: [project(1), project(2)],
      environments: [environment(5, 2)],
      suites: [suite(7, 1, 3)],
    };

    expect(startSteps(state)[5]).toMatchObject({ done: false, href: null });
  });

  it("counts the server default channel or any project webhook as a channel", () => {
    const base: StartState = { ...empty, projects: [project(1)] };

    expect(startSteps(base)[4].done).toBe(false);
    expect(startSteps({ ...base, defaultChannel: true })[4].done).toBe(true);
    expect(startSteps({ ...base, projects: [project(1), project(2, true)] })[4].done).toBe(true);
  });

  it("shows only until the first schedule exists", () => {
    expect(showGettingStarted(empty)).toBe(true);
    expect(showGettingStarted({ ...empty, schedules: [schedule] })).toBe(false);
    expect(startSteps({ ...empty, schedules: [schedule] })[5]).toMatchObject({ done: true, href: null });
  });
});
