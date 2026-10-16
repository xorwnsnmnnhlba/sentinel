"use client";

import { useState } from "react";

/**
 * 수정 화면을 연 때의 version (낙관적 잠금). 저장 요청에 함께 보내 그사이 다른 곳에서 고쳤으면 Spring이 409로 거부.
 * 화면이 열려 있는 동안 새로 그려져도(충돌 후 refresh 등) 처음 값을 유지. 새 값으로 바꾸면 다시 누른 저장이
 * 열어 둔 옛 내용으로 덮어씀. 대화상자를 닫았다 열면 새로 마운트되어 최신 값
 */
export function OpenedVersion({ version }: { version: number }) {
  const [opened] = useState(version);
  return <input type="hidden" name="version" value={opened} readOnly />;
}
