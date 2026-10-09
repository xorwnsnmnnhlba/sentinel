import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { EnvironmentItem } from "@/lib/api/types";

import { EnvironmentFields } from "./entity-fields";

const dev: EnvironmentItem = {
  id: 3,
  project: { id: 1, name: "project-1" },
  name: "dev",
  baseUrl: "https://gw.dev.internal",
  variables: { apiPrefix: "/api" },
  secretNames: ["token"],
};

describe("EnvironmentFields", () => {
  it("fills a copy with the original's URL and variables under the copy name", () => {
    render(<EnvironmentFields copy={{ from: dev, name: "dev (복사)" }} />);

    expect(screen.getByLabelText("이름")).toHaveValue("dev (복사)");
    expect(screen.getByLabelText(/기본 URL/)).toHaveValue("https://gw.dev.internal");
    expect(screen.getByLabelText("변수 1 이름")).toHaveValue("apiPrefix");
    expect(screen.getByLabelText("변수 1 값")).toHaveValue("/api");
  });

  it("asks for secret values again because they cannot be copied", () => {
    render(<EnvironmentFields copy={{ from: dev, name: "dev (복사)" }} />);

    expect(screen.getByText(/시크릿은 값을 옮길 수 없어 이름만 채웠습니다/)).toBeInTheDocument();
    expect(screen.getByLabelText("새 시크릿 1 이름")).toHaveValue("token");
    expect(screen.getByLabelText("새 시크릿 1 값")).toHaveValue("");
    // 원본의 저장된 시크릿으로 표시하지 않음 (복제본에는 아직 없음)
    expect(screen.queryByText("저장됨")).not.toBeInTheDocument();
  });

  it("keeps the notice away when the original has no secrets", () => {
    render(<EnvironmentFields copy={{ from: { ...dev, secretNames: [] }, name: "dev (복사)" }} />);

    expect(screen.queryByText(/이름만 채웠습니다/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText("새 시크릿 1 이름")).not.toBeInTheDocument();
  });
});
