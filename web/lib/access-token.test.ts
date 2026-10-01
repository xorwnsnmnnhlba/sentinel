import { describe, expect, it } from "vitest";

import { runCommand } from "./access-token";

describe("runCommand", () => {
  it("uses the site address and leaves the token to a CI secret", () => {
    expect(runCommand("https://sentinel.team.internal")).toBe(
      [
        "SENTINEL_URL=https://sentinel.team.internal \\",
        "  SENTINEL_TOKEN=$SENTINEL_TOKEN \\",
        "  scripts/sentinel-run.sh --suite <스위트 ID> --environment <환경 ID>",
      ].join("\n"),
    );
  });
});
