import { TextField } from "@/components/catalog/field";
import { MAX_WEBHOOK_URL_LENGTH } from "@/lib/webhook";

/** Webhook 주소 입력. 저장된 주소는 보안상 불러오지 않으므로 변경 시 새로 입력 */
export function WebhookFields({ configured }: { configured: boolean }) {
  return (
    <TextField
      name="webhookUrl"
      label="Webhook 주소"
      type="url"
      required
      maxLength={MAX_WEBHOOK_URL_LENGTH}
      placeholder="https://hooks.example.com/services/..."
      hint={
        configured
          ? "지정된 주소는 보안상 표시하지 않습니다. 바꿀 주소를 새로 입력해 주세요 (암호화하여 저장)"
          : "Slack·Teams 등 수신 Webhook 주소. 암호화하여 저장하며 다시 표시하지 않습니다"
      }
    />
  );
}
