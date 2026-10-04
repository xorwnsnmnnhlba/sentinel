import { ChangePasswordForm } from "@/components/auth/change-password-form";
import { Panel } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";

export const metadata = { title: "비밀번호 변경 · Sentinel" };

export default function ChangePasswordPage() {
  return (
    <div className="max-w-lg space-y-4">
      <PageHeader title="비밀번호 변경" />
      <Panel title="새 비밀번호" meta="변경하면 모든 기기에서 로그아웃">
        <div className="p-4">
          <ChangePasswordForm />
        </div>
      </Panel>
    </div>
  );
}
