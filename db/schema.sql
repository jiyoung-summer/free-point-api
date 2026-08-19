-- Free Point System — schema DDL
-- Source: resources/erd_core.svg ("Free Point System — ERD (Core)")
-- H2 (MODE=MySQL) / MySQL 8 호환. amount 계열 컬럼은 전부 BIGINT, 1원 단위(KRW).
--
-- 설계 메모 (ERD 범례 그대로):
--   PK Primary Key / UK Unique Key / FK = 논리적 외래키 (물리 FOREIGN KEY 제약은 걸지 않는다)
--   잔액은 별도 컬럼으로 두지 않고 항상 아래로 계산한다:
--     SUM(point_lot.remaining_amount) WHERE status = 'ACTIVE' AND remaining_amount > 0 AND expire_at > now()
--   사용 우선순위: point_lot.use_priority ASC (수기 우선) → expire_at ASC → id ASC

-- ---------------------------------------------------------------------------
-- point_account : 사용자별 계정. 잔액 변경(적립/적립취소/사용/사용취소)의 비관적 락(FOR UPDATE) 기준점.
--                 balance 컬럼은 두지 않는다 — 잔액은 point_lot 합계로 계산한다.
-- ---------------------------------------------------------------------------
CREATE TABLE point_account
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_point_account_user_id UNIQUE (user_id)
);

-- ---------------------------------------------------------------------------
-- point_policy : 단일 정책이 아니라 정책 "버전" 원장. append-only, UPDATE 금지.
--                유효 정책 = applied_from <= now() 인 행 중 가장 최근 1건.
--                하드코딩 금지 대상(1회 적립 한도, 개인별 최대 보유 한도)을 여기서 런타임으로 제어한다.
-- ---------------------------------------------------------------------------
CREATE TABLE point_policy
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_label        VARCHAR(100) NOT NULL,
    min_earn_amount      BIGINT       NOT NULL,
    max_earn_amount      BIGINT       NOT NULL,
    max_hold_amount      BIGINT       NOT NULL,
    default_expire_days  INT          NOT NULL,
    min_expire_days      INT          NOT NULL,
    max_expire_days      INT          NOT NULL,
    applied_from         DATETIME     NOT NULL,
    changed_by           VARCHAR(100),
    change_reason        VARCHAR(500),
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- point_transaction : 거래 원장. EARN / EARN_CANCEL / USE / USE_CANCEL 행위가 1건씩 기록된다.
--                      point_key 는 서버가 발급하는 UUID로, 거래 결과를 가리키는 외부 식별자일 뿐
--                      재시도 멱등성 키는 아니다(재시도마다 새 UUID가 나온다) — 요청 재시도 멱등성은
--                      idempotency_key 테이블이 별도로 담당한다.
--                      user_id 는 조회 편의를 위한 비정규화 컬럼이고, 정규 관계는 account_id다.
--                      display_code : 사용자 노출 문구는 메시지 번들에서 조회한다(행 크기 최소화 목적).
--                      운영자 개입 사유 등은 이 테이블 책임이 아니고, 별도 admin_audit_log(확장 범위)가 담당한다.
-- ---------------------------------------------------------------------------
CREATE TABLE point_transaction
(
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    point_key               VARCHAR(40)  NOT NULL,
    account_id              BIGINT       NOT NULL, -- FK(logical) -> point_account.id
    user_id                 BIGINT       NOT NULL,
    transaction_type        VARCHAR(20)  NOT NULL, -- EARN | EARN_CANCEL | USE | USE_CANCEL
    amount                  BIGINT       NOT NULL,
    order_no                VARCHAR(50),
    related_transaction_id  BIGINT,                -- FK(logical) -> point_transaction.id (취소 거래가 원거래를 가리킴)
    display_code            VARCHAR(30),
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_point_transaction_point_key UNIQUE (point_key)
);

-- ---------------------------------------------------------------------------
-- point_lot : 적립 단위(Lot). EARN 거래 1건 = 1행. 적립금액/사용가능잔액/만료일/적립출처를 보유한다.
--             earn_source : ORDER | EVENT | MANUAL | COMPENSATION
--             use_priority : earn_source 매핑값 (수기 지급이 낮은 값으로 우선 소진)
--             origin_lot_id : 만료된 적립분을 사용취소로 재적립할 때, 원본 Lot을 가리킨다.
--             status : ACTIVE | EXPIRED | CANCELED
-- ---------------------------------------------------------------------------
CREATE TABLE point_lot
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    point_key            VARCHAR(40)  NOT NULL,
    user_id              BIGINT       NOT NULL,
    earn_transaction_id  BIGINT       NOT NULL, -- FK(logical) -> point_transaction.id
    policy_id            BIGINT       NOT NULL, -- FK(logical) -> point_policy.id
    earn_source          VARCHAR(30)  NOT NULL,
    use_priority         INT          NOT NULL,
    origin_lot_id        BIGINT,                -- FK(logical) -> point_lot.id (자기 참조, 재적립 시에만 설정)
    amount               BIGINT       NOT NULL,
    remaining_amount     BIGINT       NOT NULL,
    expire_at            DATETIME     NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_point_lot_earn_transaction_id UNIQUE (earn_transaction_id)
);

-- ---------------------------------------------------------------------------
-- point_use_detail : 사용 상세. (사용거래 x 적립분) 매핑으로 1원 단위 사용 추적과 부분취소를 지원한다.
-- ---------------------------------------------------------------------------
CREATE TABLE point_use_detail
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    use_transaction_id   BIGINT       NOT NULL, -- FK(logical) -> point_transaction.id
    point_lot_id         BIGINT       NOT NULL, -- FK(logical) -> point_lot.id
    user_id              BIGINT       NOT NULL,
    order_no             VARCHAR(50)  NOT NULL,
    amount               BIGINT       NOT NULL,
    canceled_amount      BIGINT       NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- point_use_cancel_detail : 사용취소 상세. 어떤 사용상세를 얼마나 되돌렸는지,
--                            만료로 인한 재적립 여부(reissued_lot_id)를 기록한다.
--                            use_detail_id 가 source of truth이고, point_lot_id 는 조회 편의상 비정규화한 값(도출 가능).
--                            reissued_lot_id 가 NOT NULL 이면 "만료되어 신규 재적립됨"을 의미한다.
-- ---------------------------------------------------------------------------
CREATE TABLE point_use_cancel_detail
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    cancel_transaction_id  BIGINT   NOT NULL, -- FK(logical) -> point_transaction.id
    use_detail_id          BIGINT   NOT NULL, -- FK(logical) -> point_use_detail.id
    point_lot_id           BIGINT   NOT NULL, -- FK(logical) -> point_lot.id
    amount                 BIGINT   NOT NULL,
    reissued_lot_id        BIGINT,            -- FK(logical) -> point_lot.id (만료분 재적립 시에만 설정)
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- idempotency_key : 상태 변경 요청의 재시도 멱등성 보장. (user_id, operation_type, idempotency_key)
--                    조합이 최초 처리 결과다 — 같은 조합으로 재요청하면 비즈니스 로직을 다시 타지 않고
--                    response_body를 그대로 반환한다. operation_type을 포함하는 이유는 클라이언트가
--                    같은 키를 실수로 재사용해도 적립/사용처럼 서로 다른 작업까지 뒤섞이지 않게 하기 위함이다.
--                    request_hash : 같은 키로 "다른 내용"의 요청이 오는 오용을 잡아내는 지문(SHA-256).
--                    (user_id, operation_type, idempotency_key)만으로는 요청 내용을 비교할 수 없어서,
--                    같은 키로 적립취소 A를 성공시킨 뒤 같은 키로 적립취소 B를 요청하면 B 대신 A의 캐시된
--                    응답이 반환되는 문제가 있었다 — 조회 시 request_hash가 다르면 캐시를 쓰지 않고 거절한다.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_key
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    operation_type   VARCHAR(30)  NOT NULL, -- EARN | MANUAL_EARN | EARN_CANCEL | USE | USE_CANCEL
    idempotency_key  VARCHAR(200) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL, -- SHA-256(요청 내용 지문) — 같은 키의 재사용/오용 구분
    response_body    TEXT         NOT NULL, -- 최초 처리 응답(JSON) 그대로 저장
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_idempotency_key UNIQUE (user_id, operation_type, idempotency_key)
);

-- ---------------------------------------------------------------------------
-- 인덱스
-- ---------------------------------------------------------------------------

-- 유효 정책 조회: applied_from <= now() 중 최신 1건
CREATE INDEX idx_point_policy_applied_from ON point_policy (applied_from DESC);

-- 거래 내역 조회(PointTransactionRepository.findByUserId)는 account_id가 아니라 user_id로
-- 필터링한다 — user_id가 "조회 편의를 위한 비정규화 컬럼"이면서 정작 인덱스는 실제로 쓰이지
-- 않는 account_id 기준으로 잡혀 있던 것을 바로잡았다(실제로 쓰는 컬럼 기준으로 재정의).
CREATE INDEX idx_point_transaction_user_created ON point_transaction (user_id, created_at DESC);
CREATE INDEX idx_point_transaction_related ON point_transaction (related_transaction_id);

-- 사용 가능 Lot 조회 + 소진 우선순위: use_priority ASC(수기 우선) → expire_at ASC → id ASC
CREATE INDEX idx_point_lot_usable ON point_lot (user_id, status, use_priority, expire_at, id);
CREATE INDEX idx_point_lot_policy ON point_lot (policy_id);
CREATE INDEX idx_point_lot_origin ON point_lot (origin_lot_id);
-- earnCancel()의 findByPointKey가 이 인덱스 없이는 point_lot 전체를 스캔한다
-- (point_transaction.point_key는 UNIQUE 제약으로 자동 인덱싱되지만, point_lot.point_key는
-- UNIQUE가 아니라서 — earn_transaction_id UK가 실질적 유일성을 보장 — 별도 인덱스가 필요했다).
CREATE INDEX idx_point_lot_point_key ON point_lot (point_key);

CREATE INDEX idx_point_use_detail_use_transaction ON point_use_detail (use_transaction_id);
CREATE INDEX idx_point_use_detail_point_lot ON point_use_detail (point_lot_id);
CREATE INDEX idx_point_use_detail_order_no ON point_use_detail (order_no);

CREATE INDEX idx_use_cancel_detail_cancel_transaction ON point_use_cancel_detail (cancel_transaction_id);
CREATE INDEX idx_use_cancel_detail_use_detail ON point_use_cancel_detail (use_detail_id);
CREATE INDEX idx_use_cancel_detail_point_lot ON point_use_cancel_detail (point_lot_id);
CREATE INDEX idx_use_cancel_detail_reissued_lot ON point_use_cancel_detail (reissued_lot_id);
