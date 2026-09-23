import { describe, expect, it } from "vitest";

import type { CaseImportCandidate } from "@/lib/api/types";

import { type CandidateRow, isImportCase, rowIssues, toImportCases, toRows } from "./case-import";

function candidate(source: string, name: string, conflict = false): CaseImportCandidate {
  return {
    source,
    name,
    request: { method: "GET", path: source.split(" ")[1], headers: {}, body: null },
    assertions: [{ type: "statusCode", anyOf: ["200"] }],
    warnings: [],
    conflict,
  };
}

describe("toRows", () => {
  it("starts with every candidate selected except names already in the suite", () => {
    const rows = toRows([candidate("GET /a", "A"), candidate("GET /b", "B", true)]);

    expect(rows.map((row) => row.selected)).toEqual([true, false]);
  });
});

describe("rowIssues", () => {
  const existing = new Set(["Existing"]);

  function rows(...entries: [string, boolean][]): CandidateRow[] {
    return entries.map(([name, selected], index) => ({ ...candidate(`GET /${index}`, name), selected }));
  }

  it("flags blank, existing and repeated names among the selected rows", () => {
    const issues = rowIssues(
      rows(["  ", true], ["Existing", true], ["Twice", true], [" Twice ", true], ["Ok", true]),
      existing,
    );

    expect(Object.fromEntries(issues)).toEqual({
      0: "이름을 입력해 주세요",
      1: "스위트에 이미 있는 이름입니다",
      2: "가져올 목록 안에서 이름이 겹칩니다",
      3: "가져올 목록 안에서 이름이 겹칩니다",
    });
  });

  it("ignores rows that are not selected", () => {
    const issues = rowIssues(rows(["Existing", false], ["Twice", true], ["Twice", false]), existing);

    expect(issues.size).toBe(0);
  });
});

describe("toImportCases", () => {
  it("sends only the selected cases without screen-only fields", () => {
    const [first, second] = toRows([candidate("GET /a", "A"), candidate("GET /b", "B")]);

    expect(
      toImportCases([
        { ...first, name: "  Renamed  " },
        { ...second, selected: false },
      ]),
    ).toEqual([
      {
        name: "Renamed",
        request: { method: "GET", path: "/a", headers: {}, body: null },
        assertions: [{ type: "statusCode", anyOf: ["200"] }],
      },
    ]);
  });
});

describe("isImportCase", () => {
  it.each([
    [{ name: "A", request: { method: "GET", path: "/a" } }, true],
    [{ name: "A", request: { method: "GET", path: "/a" }, assertions: [] }, true],
    [{ name: "A", request: { method: "GET", path: "/a" }, assertions: {} }, false],
    [{ name: 1, request: { method: "GET", path: "/a" } }, false],
    [{ name: "A", request: { method: "GET" } }, false],
    [{ name: "A", request: null }, false],
    [null, false],
    ["A", false],
  ])("checks the shape of %j", (value, expected) => {
    expect(isImportCase(value)).toBe(expected);
  });
});
