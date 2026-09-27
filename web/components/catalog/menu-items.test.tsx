import { describe, expect, it } from "vitest";

import { EDIT_DELETE, EDIT_DUPLICATE_DELETE, withHistory } from "./menu-items";

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
