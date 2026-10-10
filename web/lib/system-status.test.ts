import { describe, expect, it } from "vitest";

import type { SystemStatus } from "@/lib/api/types";
import { secretStateText, settingRows, undecryptableLocation } from "@/lib/system-status";

const status: SystemStatus = {
  build: { version: "0.0.1-SNAPSHOT", builtAt: "2026-10-10T07:43:38Z" },
  startedAt: "2026-10-10T07:43:47Z",
  databaseMigration: "29",
  secrets: { state: "ENABLED", undecryptable: [] },
  defaultChannel: true,
  resultLinks: false,
  heartbeat: false,
  retention: { rawResultDays: 30, notificationDeliveryDays: 90 },
};

describe("secretStateText", () => {
  it("needs nothing while the key is in use", () => {
    expect(secretStateText("ENABLED")).toEqual({ label: "사용 중", tone: "ok", note: null });
  });

  it("tells what to do while rotating or without a key", () => {
    const rotating = secretStateText("ROTATING");
    expect(rotating.label).toBe("키 교체 중");
    expect(rotating.tone).toBe("warning");
    expect(rotating.note).toContain("SENTINEL_SECRET_KEY_PREVIOUS 줄 삭제");

    const disabled = secretStateText("DISABLED");
    expect(disabled.label).toBe("꺼짐");
    expect(disabled.tone).toBe("warning");
    expect(disabled.note).toContain("SENTINEL_SECRET_KEY가 없어");
  });
});

describe("undecryptableLocation", () => {
  const project = { id: 2, name: "결제" };

  it("names the project webhook or the environment secret", () => {
    expect(undecryptableLocation({ project, environment: null, secret: null })).toBe("프로젝트 Webhook 주소");
    expect(undecryptableLocation({ project, environment: { id: 3, name: "dev" }, secret: "token" })).toBe(
      "환경 dev · 시크릿 token",
    );
  });
});

describe("settingRows", () => {
  it("lists each setting with its variable and whether it is configured", () => {
    expect(settingRows(status).map(({ name, variable, configured }) => [name, variable, configured])).toEqual([
      ["기본 알림 채널", "SENTINEL_WEBHOOK_URL", true],
      ["결과 링크 주소", "SENTINEL_WEB_URL", false],
      ["외부 생존 신호", "SENTINEL_HEARTBEAT_URL", false],
    ]);
    expect(settingRows({ ...status, heartbeat: true })[2].configured).toBe(true);
    expect(settingRows(status)[1].whenMissing).toBe("알림에 결과 화면 링크가 붙지 않음");
  });
});
