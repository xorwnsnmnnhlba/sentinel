import { describe, expect, it } from "vitest";

import { EDIT_DELETE, EDIT_DUPLICATE_DELETE, ENVIRONMENT_MENU, maintenanceMenu, withHistory } from "./menu-items";

describe("maintenance menus", () => {
  it("offers registering maintenance for an environment before deletion", () => {
    expect(ENVIRONMENT_MENU.map((item) => item.label)).toEqual(["수정", "복제", "점검 시간 등록", "삭제"]);
  });

  it("ends an active window and cancels a planned one", () => {
    expect(maintenanceMenu(true).map((item) => item.label)).toEqual(["수정", "지금 종료"]);
    expect(maintenanceMenu(false).map((item) => item.label)).toEqual(["수정", "취소"]);
    expect(maintenanceMenu(true).at(-1)?.destructive).toBe(true);
  });
});

describe("withHistory", () => {
  it("puts the history link right before deletion", () => {
    expect(withHistory(EDIT_DUPLICATE_DELETE, "/change-logs?projectId=3").map((item) => item.id)).toEqual([
      "edit",
      "duplicate",
      "history",
      "delete",
    ]);
    expect(withHistory(EDIT_DELETE, "/h").find((item) => item.id === "history")).toEqual({
      id: "history",
      label: "변경 이력",
      icon: "history",
      href: "/h",
    });
  });

  it("appends it when nothing is destructive", () => {
    expect(withHistory([{ id: "edit", label: "수정", icon: "edit" }], "/h").map((item) => item.id)).toEqual([
      "edit",
      "history",
    ]);
  });
});
