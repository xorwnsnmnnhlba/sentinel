import { Badge } from "@/components/ui/badge";
import type { NotificationDeliveryItem, NotificationTone } from "@/lib/api/types";
import { resultLabel, TONE_LABELS } from "@/lib/notification-delivery";

const toneStyle: Record<NotificationTone, string> = {
  FAILURE: "bg-destructive/10 text-destructive",
  WARNING: "bg-amber-500/10 text-amber-800 dark:text-amber-400",
  SUCCESS: "bg-emerald-500/10 text-emerald-800 dark:text-emerald-400",
  INFO: "bg-muted text-muted-foreground",
};

/** 알림 성격 (실패·주의·복구·안내). 채널 서식의 색과 같은 구분 */
export function ToneBadge({ tone }: { tone: NotificationTone }) {
  return <Badge className={toneStyle[tone]}>{TONE_LABELS[tone]}</Badge>;
}

/** 발송 결과. 보내지 못한 것만 눈에 띄게 (실패는 빨강, 보냄·로그 기록은 옅게) */
export function DeliveryResultBadge({
  item,
}: {
  item: Pick<NotificationDeliveryItem, "delivered" | "format" | "statusCode" | "error">;
}) {
  if (item.delivered) {
    return <Badge variant="outline">{resultLabel(item)}</Badge>;
  }
  return (
    // 사유 문구가 길어 좁은 화면에서는 줄바꿈
    <Badge className="h-auto bg-destructive/10 text-left whitespace-normal text-destructive">
      <span aria-hidden className="size-1.5 rounded-full bg-current" />
      {resultLabel(item)}
    </Badge>
  );
}
