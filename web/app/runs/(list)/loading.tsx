/** 목록 전용 로딩 화면. 상세(/runs/[id])까지 감싸면 notFound()가 200 스트리밍 후 렌더링되어 404 상태 코드를 잃음 */
import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-8 w-72" />
      <div className="space-y-2">
        {Array.from({ length: 8 }, (_, index) => (
          <Skeleton key={index} className="h-10 w-full" />
        ))}
      </div>
    </div>
  );
}
