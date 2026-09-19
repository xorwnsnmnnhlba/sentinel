import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/** 라벨 + 입력. maxLength는 Spring 컬럼 길이와 일치 */
export function TextField({
  name,
  label,
  defaultValue,
  required,
  maxLength,
  placeholder,
  hint,
  multiline,
  type = "text",
}: {
  name: string;
  label: string;
  defaultValue?: string | null;
  required?: boolean;
  maxLength?: number;
  placeholder?: string;
  hint?: string;
  multiline?: boolean;
  type?: "text" | "url";
}) {
  const id = `field-${name}`;
  const props = {
    id,
    name,
    defaultValue: defaultValue ?? "",
    required,
    maxLength,
    placeholder,
    "aria-describedby": hint ? `${id}-hint` : undefined,
  };
  return (
    <div className="space-y-2">
      <Label htmlFor={id}>
        {label}
        {!required && <span className="font-normal text-muted-foreground">(선택)</span>}
      </Label>
      {multiline ? <Textarea rows={3} {...props} /> : <Input type={type} {...props} />}
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
    </div>
  );
}
