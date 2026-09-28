import { Badge } from "@/components/ui/badge";

/** HTTP 메서드. 보라 계열 옅은 바탕 (DESIGN.md --brand-violet-soft) */
export function MethodBadge({ method }: { method: string }) {
  return <Badge className="shrink-0 rounded-sm bg-brand-violet-soft font-mono text-brand-violet-deep">{method}</Badge>;
}
