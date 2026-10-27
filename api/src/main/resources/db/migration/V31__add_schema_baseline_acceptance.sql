-- 구조 변화 기준선 저장 (docs/design.md "스키마 드리프트 감지" 3단계)
-- schema_pending: 기록할 때 호환이 깨지는 변화(BREAKING)·null 전환(WARNING)이 있어 기준선에 넣지 않은 결과 (승인 대기).
--   기준선은 보류가 아닌 지문의 최근 실행으로 계산하므로 같은 변화가 이어지면 매 실행 보고. 새 필드(INFO)만 있으면 바로 반영.
--   기본값이 상수라 기존 행을 고쳐 쓰지 않고 추가 (기존 결과는 모두 반영된 것으로 봄 = 지금까지의 판정과 같음)
-- schema_tolerance: 기준선이 창(최근 실행)과 무관하게 영구히 허용하는 변동. kind: NULLABLE(null 관측) / OPTIONAL(없던 적 있음)
--   first_result_id: 이 변동을 남긴 결과. 그보다 앞선 결과의 판정에는 쓰지 않음 (지난 실행의 판정이 나중 기록으로 바뀌지 않도록)
--   경로가 1000자를 넘으면 남기지 않음 (창 안에서만 허용)

ALTER TABLE test_result ADD COLUMN schema_pending BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE schema_tolerance (
    test_case_id    BIGINT        NOT NULL,
    environment_id  BIGINT        NOT NULL,
    path            VARCHAR(1000) NOT NULL,
    kind            VARCHAR(10)   NOT NULL,
    first_result_id BIGINT        NOT NULL,
    PRIMARY KEY (test_case_id, environment_id, path, kind)
);
