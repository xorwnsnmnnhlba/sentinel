import { Badge } from "@/components/ui/badge";

/** HTTP 메서드. 청록 계열 옅은 바탕 (DESIGN.md --brand-soft) */
export function MethodBadge({ method }: { method: string }) {
  return <Badge className="shrink-0 rounded-sm bg-brand-soft font-mono text-brand-deep">{method}</Badge>;
}
