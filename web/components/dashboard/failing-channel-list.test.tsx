import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { ChannelFailure, NotificationChannel } from "@/lib/api/types";

import { FailingChannelList } from "./failing-channel-list";

type Failing = NotificationChannel & { failing: ChannelFailure };

const billing: Failing = {
  channel: "PROJECT",
  project: { id: 3, name: "billing" },
  configured: true,
  format: "SLACK",
  failing: {
    since: "2026-10-05T05:20:00Z",
    failures: 3,
    lastSentAt: "2026-10-05T07:00:00Z",
    statusCode: 404,
    error: null,
  },
};

const defaultChannel: Failing = {
  channel: "DEFAULT",
  project: null,
  configured: true,
  format: "TEAMS",
  failing: {
    since: "2026-10-05T00:00:00Z",
    failures: 1,
    lastSentAt: "2026-10-05T00:00:00Z",
    statusCode: null,
    error: "CONNECTION_FAILED",
  },
};

describe("FailingChannelList", () => {
  it("shows each broken channel with the last failure and links to its failed deliveries", () => {
    render(<FailingChannelList channels={[billing, defaultChannel]} />);

    const items = within(screen.getByRole("list", { name: "알림을 보내지 못하는 채널" })).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("billing · Slack");
    expect(items[0]).toHaveTextContent("HTTP 404");
    expect(items[0]).toHaveTextContent("10-05 14:20부터 3건");
    expect(items[0]).toHaveTextContent("Webhook이 삭제되었거나 주소가 바뀜");
    expect(within(items[0]).getByRole("link", { name: "billing · Slack" })).toHaveAttribute(
      "href",
      "/notifications?projectId=3&failed=true",
    );

    expect(items[1]).toHaveTextContent("기본 채널 · Teams");
    expect(items[1]).toHaveTextContent("연결하지 못했습니다");
    expect(items[1]).toHaveTextContent("내부망·프록시에서 채널 주소로 나갈 수 있는지 확인");
    expect(within(items[1]).getByRole("link")).toHaveAttribute("href", "/notifications?failed=true");
  });
});
