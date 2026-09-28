import { describe, expect, it } from "vitest";

import type { ChangeLogItem } from "@/lib/api/types";

import {
  actionLabel,
  fieldLabel,
  historyHref,
  isTargetType,
  projectHistoryHref,
  suiteHistoryHref,
  targetHref,
} from "./change-log";

function log(patch: Partial<ChangeLogItem>): ChangeLogItem {
  return {
    id: 1,
    occurredAt: "2026-09-27T01:00:00Z",
    actor: { id: 1, name: "admin@sentinel.test" },
    targetType: "CASE",
    targetId: 7,
    targetName: "health",
    projectId: 3,
    suiteId: 5,
    action: "UPDATED",
    fields: [],
    ...patch,
  };
}

describe("fieldLabel", () => {
  it.each([
    ["name", "이름"],
    ["caseOrder", "실행 순서"],
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
    expect(actionLabel({ targetType: "USER", action: "CREATED" })).toBe("가입");
    expect(actionLabel({ targetType: "INVITE", action: "CREATED" })).toBe("발급");
    expect(actionLabel({ targetType: "USER", action: "UPDATED" })).toBe("수정");
    expect(actionLabel({ targetType: "CASE", action: "CREATED" })).toBe("등록");
    expect(actionLabel({ targetType: "CASE", action: "DELETED" })).toBe("삭제");
  });
});

describe("targetHref", () => {
  it("links to the screen of the target", () => {
    expect(targetHref(log({ targetType: "PROJECT", targetId: 3 }))).toBe("/projects/3");
    expect(targetHref(log({ targetType: "SUITE", targetId: 5 }))).toBe("/suites/5");
    expect(targetHref(log({ targetType: "CASE", targetId: 7 }))).toBe("/cases/7");
    // 환경은 소속 프로젝트 화면
    expect(targetHref(log({ targetType: "ENVIRONMENT", targetId: 9, projectId: 3 }))).toBe("/projects/3");
  });

  it("offers no link for deletions and targets without a screen", () => {
    expect(targetHref(log({ action: "DELETED" }))).toBeNull();
    expect(targetHref(log({ targetType: "SCHEDULE" }))).toBeNull();
    expect(targetHref(log({ targetType: "USER", projectId: null }))).toBeNull();
    expect(targetHref(log({ targetType: "INVITE", projectId: null }))).toBeNull();
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
