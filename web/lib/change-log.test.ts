import { describe, expect, it } from "vitest";

import type { ChangeAction, ChangeLogItem, ChangeTargetType } from "@/lib/api/types";

import {
  actionLabel,
  fieldLabel,
  historyHref,
  isTargetType,
  projectHistoryHref,
  suiteHistoryHref,
  targetHref,
} from "./change-log";

/** 기록 1건. 대상·프로젝트는 평평하게 받아 응답 모양으로 */
function log(patch: {
  targetType?: ChangeTargetType;
  targetId?: number;
  targetName?: string;
  projectId?: number | null;
  action?: ChangeAction;
}): ChangeLogItem {
  const projectId = patch.projectId === undefined ? 3 : patch.projectId;
  return {
    id: 1,
    occurredAt: "2026-09-27T01:00:00Z",
    actor: { id: 1, name: "admin@sentinel.test" },
    target: { type: patch.targetType ?? "CASE", id: patch.targetId ?? 7, name: patch.targetName ?? "health" },
    project: projectId === null ? null : { id: projectId, name: "billing" },
    suite: { id: 5, name: "smoke" },
    action: patch.action ?? "UPDATED",
    fields: [],
  };
}

describe("fieldLabel", () => {
  it.each([
    ["name", "이름"],
    ["caseOrder", "실행 순서"],
    ["endsAt", "끝 시각"],
    ["reason", "사유"],
    ["skipHolidays", "휴일 건너뛰기"],
    ["passwordResetLink", "비밀번호 재설정 링크 발급"],
    ["variables.userId", "변수 userId"],
    ["secrets.token", "시크릿 token"],
    ["unknownField", "unknownField"],
  ])("shows %s as %s", (field, label) => {
    expect(fieldLabel(field)).toBe(label);
  });
});

describe("actionLabel", () => {
  it("names sign-ups and invites after what happened", () => {
    expect(actionLabel({ target: { type: "USER" }, action: "CREATED" })).toBe("가입");
    expect(actionLabel({ target: { type: "INVITE" }, action: "CREATED" })).toBe("발급");
    expect(actionLabel({ target: { type: "INVITE" }, action: "DELETED" })).toBe("취소");
    expect(actionLabel({ target: { type: "USER" }, action: "UPDATED" })).toBe("수정");
    expect(actionLabel({ target: { type: "CASE" }, action: "CREATED" })).toBe("등록");
    expect(actionLabel({ target: { type: "CASE" }, action: "DELETED" })).toBe("삭제");
  });
});

describe("targetHref", () => {
  it("links to the screen of the target", () => {
    expect(targetHref(log({ targetType: "PROJECT", targetId: 3 }))).toBe("/projects/3");
    expect(targetHref(log({ targetType: "SUITE", targetId: 5 }))).toBe("/suites/5");
    expect(targetHref(log({ targetType: "CASE", targetId: 7 }))).toBe("/cases/7");
    // 환경은 소속 프로젝트 화면
    expect(targetHref(log({ targetType: "ENVIRONMENT", targetId: 9, projectId: 3 }))).toBe("/projects/3");
    // 점검 시간도 환경이 있는 프로젝트 화면
    expect(targetHref(log({ targetType: "MAINTENANCE_WINDOW", targetId: 2, projectId: 3 }))).toBe("/projects/3");
    // 휴일은 그해 휴일 화면 (이름이 "날짜 이름")
    expect(targetHref(log({ targetType: "HOLIDAY", targetName: "2027-01-01 신정", projectId: null }))).toBe(
      "/holidays?year=2027",
    );
    expect(targetHref(log({ targetType: "HOLIDAY", targetName: "신정", projectId: null }))).toBe("/holidays");
  });

  it("offers no link for deletions and targets without a screen", () => {
    expect(targetHref(log({ action: "DELETED" }))).toBeNull();
    expect(targetHref(log({ targetType: "SCHEDULE" }))).toBeNull();
    expect(targetHref(log({ targetType: "USER", projectId: null }))).toBeNull();
    expect(targetHref(log({ targetType: "INVITE", projectId: null }))).toBeNull();
    expect(targetHref(log({ targetType: "HOLIDAY", targetName: "2026-10-09 한글날", action: "DELETED" }))).toBeNull();
    expect(targetHref(log({ targetType: "ENVIRONMENT", projectId: null }))).toBeNull();
  });
});

describe("history links", () => {
  it("builds filtered change log addresses", () => {
    expect(historyHref("CASE", 7)).toBe("/change-logs?targetType=CASE&targetId=7");
    expect(projectHistoryHref(3)).toBe("/change-logs?projectId=3");
    expect(suiteHistoryHref(5)).toBe("/change-logs?suiteId=5");
  });

  it("accepts only known target types", () => {
    expect(isTargetType("SCHEDULE")).toBe(true);
    expect(isTargetType("case")).toBe(false);
    expect(isTargetType(undefined)).toBe(false);
  });
});
