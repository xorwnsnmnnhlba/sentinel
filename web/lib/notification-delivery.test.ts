import { describe, expect, it } from "vitest";

import { channelLabel, notificationsHref, resultHint, resultLabel } from "./notification-delivery";

const sent = { delivered: true, format: "SLACK" as const, statusCode: 200, error: null };

describe("channelLabel", () => {
  it("names the project or the default channel with the format", () => {
    expect(channelLabel({ project: { id: 1, name: "billing" }, format: "SLACK" })).toBe("billing · Slack");
    expect(channelLabel({ project: null, format: "TEAMS" })).toBe("기본 채널 · Teams");
    expect(channelLabel({ project: null, format: "LOG" })).toBe("기본 채널 · 로그");
    expect(channelLabel({ project: null, format: "TEXT" })).toBe("기본 채널 · 텍스트");
  });
});

describe("resultLabel", () => {
  it.each([
    [sent, "보냄"],
    [{ ...sent, format: "LOG" as const, statusCode: null }, "로그에만 기록"],
    [{ ...sent, delivered: false, statusCode: 404 }, "HTTP 404"],
    [{ ...sent, delivered: false, statusCode: null, error: "TIMEOUT" }, "응답 대기 시간이 지났습니다"],
    [
      { ...sent, delivered: false, statusCode: null, error: "BLOCKED_DESTINATION" },
      "내부·메타데이터 주소라 보내지 않았습니다",
    ],
    [{ ...sent, delivered: false, statusCode: null, error: "SOMETHING_NEW" }, "SOMETHING_NEW"],
  ])("%j → %s", (item, label) => {
    expect(resultLabel(item)).toBe(label);
  });
});

describe("resultHint", () => {
  it.each([
    [sent, null],
    [{ ...sent, format: "LOG" as const, statusCode: null }, "기본 채널 주소(SENTINEL_WEBHOOK_URL) 미설정"],
    [{ ...sent, delivered: false, statusCode: 404 }, "Webhook이 삭제되었거나 주소가 바뀜"],
    [{ ...sent, delivered: false, statusCode: 410 }, "Webhook이 삭제되었거나 주소가 바뀜"],
    [{ ...sent, delivered: false, statusCode: 401 }, "채널이 거부함 (권한·토큰 확인)"],
    [{ ...sent, delivered: false, statusCode: 403 }, "채널이 거부함 (권한·토큰 확인)"],
    [{ ...sent, delivered: false, statusCode: 429 }, "채널의 발송 한도 초과"],
    [{ ...sent, delivered: false, statusCode: 500 }, "채널 서버 오류"],
    [{ ...sent, delivered: false, statusCode: 499 }, "주소와 채널 설정 확인"],
    [{ ...sent, delivered: false, statusCode: 400 }, "주소와 채널 설정 확인"],
    [{ ...sent, delivered: false, statusCode: null, error: "TIMEOUT" }, "채널 응답이 늦음"],
    [
      { ...sent, delivered: false, statusCode: null, error: "CONNECTION_FAILED" },
      "사내망·프록시에서 채널 주소로 나갈 수 있는지 확인",
    ],
    [{ ...sent, delivered: false, statusCode: null, error: "BLOCKED_DESTINATION" }, null],
  ])("%j → %s", (item, hint) => {
    expect(resultHint(item)).toBe(hint);
  });
});

describe("notificationsHref", () => {
  it("links to all deliveries or those of one project", () => {
    expect(notificationsHref()).toBe("/notifications");
    expect(notificationsHref(3)).toBe("/notifications?projectId=3");
  });
});
