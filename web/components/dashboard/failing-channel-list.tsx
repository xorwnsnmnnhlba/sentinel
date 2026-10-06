import Link from "next/link";

import { DeliveryResultBadge } from "@/components/notification/delivery-badges";
import type { FailingChannel } from "@/lib/api/types";
import { channelLabel, failingSummary, failuresHref, resultHint } from "@/lib/notification-delivery";

/**
 * 대시보드의 알림을 보내지 못하는 채널 (오래 실패한 순). 채널을 누르면 그 채널의 보내지 못한 알림 이력
 */
export function FailingChannelList({ channels }: { channels: FailingChannel[] }) {
  return (
    <ul className="divide-y text-sm" aria-label="알림을 보내지 못하는 채널">
      {channels.map((channel) => {
        const result = {
          delivered: false,
          format: channel.format,
          statusCode: channel.statusCode,
          error: channel.error,
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
            <span className="font-mono text-xs text-muted-foreground">{failingSummary(channel)}</span>
            {hint && <span className="text-xs text-muted-foreground">{hint}</span>}
          </li>
        );
      })}
    </ul>
  );
}
