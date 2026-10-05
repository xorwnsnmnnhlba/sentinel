import { describe, expect, it } from "vitest";

import { activeHref } from "./sidebar-nav";

describe("activeHref", () => {
  it("highlights home only on the root path", () => {
    expect(activeHref("/")).toBe("/");
  });

  it("keeps projects highlighted on the suite and case screens under it", () => {
    for (const path of ["/projects", "/projects/3", "/suites/10", "/cases/7", "/cases/new"]) {
      expect(activeHref(path), path).toBe("/projects");
    }
  });

  it("matches runs and users by their own paths", () => {
    expect(activeHref("/runs")).toBe("/runs");
    expect(activeHref("/runs/5")).toBe("/runs");
    expect(activeHref("/users")).toBe("/users");
    expect(activeHref("/holidays")).toBe("/holidays");
  });

  it("highlights nothing for screens outside the menu or paths that only share a prefix", () => {
    expect(activeHref("/settings/password")).toBeUndefined();
    expect(activeHref("/runs-archive")).toBeUndefined();
    expect(activeHref("/projectsx")).toBeUndefined();
  });
});
