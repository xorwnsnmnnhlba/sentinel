import { describe, expect, it } from "vitest";

import { deliveryMessage, webhookUrlError } from "./webhook";

describe("webhookUrlError", () => {
  it("accepts absolute http and https URLs", () => {
    expect(webhookUrlError(" https://hooks.example.com/services/T1/B2 ")).toBeNull();
    expect(webhookUrlError("http://messenger.internal:8080/hook")).toBeNull();
  });

  it.each([
    ["", "Webhook 주소를 입력해 주세요"],
    ["hooks.example.com/x", "http:// 또는 https://로 시작하는 주소를 입력해 주세요"],
    ["ftp://hooks.example.com/x", "http:// 또는 https://로 시작하는 주소를 입력해 주세요"],
    [`https://hooks.example.com/${"a".repeat(1000)}`, "Webhook 주소는 1000자 이하입니다"],
  ])("explains %j", (value, message) => {
    expect(webhookUrlError(value)).toBe(message);
  });
});

describe("deliveryMessage", () => {
  it("asks to check the channel after sending", () => {
    expect(deliveryMessage({ delivered: true, statusCode: 200, error: null })).toBe(
      "테스트 알림을 보냈습니다 (HTTP 200). 채널에 메시지가 왔는지 확인해 주세요",
    );
  });

  it("shows the status code the channel refused with", () => {
    expect(deliveryMessage({ delivered: false, statusCode: 404, error: null })).toBe(
      "채널이 받지 않았습니다 (HTTP 404). 주소와 채널 설정을 확인해 주세요",
    );
  });

  it.each([
    ["CONNECTION_FAILED", "연결하지 못했습니다"],
    ["BLOCKED_DESTINATION", "내부·메타데이터 주소라 보내지 않았습니다"],
    ["SOMETHING_NEW", "SOMETHING_NEW"],
  ])("explains why nothing was received: %s", (error, reason) => {
    expect(deliveryMessage({ delivered: false, statusCode: null, error })).toBe(
      `테스트 알림을 보내지 못했습니다: ${reason}`,
    );
  });
});
