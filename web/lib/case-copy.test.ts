import { describe, expect, it } from "vitest";

import { copyName, MAX_CASE_NAME_LENGTH } from "./case-copy";

describe("copyName", () => {
  it("marks the copy", () => {
    expect(copyName("주문 조회", ["주문 조회"])).toBe("주문 조회 (복사)");
  });

  it("numbers further copies until the name is free", () => {
    expect(copyName("주문 조회", ["주문 조회", "주문 조회 (복사)", "주문 조회 (복사 2)"])).toBe("주문 조회 (복사 3)");
  });

  it("copies a copy by adding another mark", () => {
    expect(copyName("주문 조회 (복사)", ["주문 조회 (복사)"])).toBe("주문 조회 (복사) (복사)");
  });

  it("shortens long names so the copy fits the length limit", () => {
    const long = "가".repeat(MAX_CASE_NAME_LENGTH);

    const first = copyName(long, [long]);
    const second = copyName(long, [long, first]);

    expect(first).toHaveLength(MAX_CASE_NAME_LENGTH);
    expect(first.endsWith(" (복사)")).toBe(true);
    expect(second).toHaveLength(MAX_CASE_NAME_LENGTH);
    expect(second.endsWith(" (복사 2)")).toBe(true);
  });

  it("does not leave a space before the mark when cutting at a space", () => {
    const name = "a".repeat(MAX_CASE_NAME_LENGTH - 6) + " tail";

    expect(copyName(name, [])).toBe("a".repeat(MAX_CASE_NAME_LENGTH - 6) + " (복사)");
  });
});
