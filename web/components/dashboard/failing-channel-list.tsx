import Link from "next/link";

import { DeliveryResultBadge } from "@/components/notification/delivery-badges";
import type { ChannelFailure, NotificationChannel } from "@/lib/api/types";
import { channelLabel, failingSummary, failuresHref, resultHint } from "@/lib/notification-delivery";

/**
 * 대시보드의 알림을 보내지 못하는 채널 (오래 실패한 순). 채널을 누르면 그 채널의 보내지 못한 알림 이력
 */
export function FailingChannelList({ channels }: { channels: (NotificationChannel & { failing: ChannelFailure })[] }) {
  return (
    <ul className="divide-y text-sm" aria-label="알림을 보내지 못하는 채널">
      {channels.map((channel) => {
        // 결과 문구는 서식과 무관 (로그 기록이 아닌 한), 서식을 모르면 텍스트로 취급
        const result = {
          delivered: false,
          format: channel.format ?? ("TEXT" as const),
          statusCode: channel.failing.statusCode,
          error: channel.failing.error,
        };
        const hint = resultHint(result);
        return (
          <li
            key={`${channel.channel}-${channel.project?.id ?? "default"}`}
            className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5"
          >
            <Link href={failuresHref(channel)} className="font-medium hover:underline">
              {channelLabel(channel)}
            </Link>
            <DeliveryResultBadge item={result} />
            <span className="font-mono text-xs text-muted-foreground">{failingSummary(channel.failing)}</span>
            {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
          </li>
        );
      })}
    </ul>
  );
}
