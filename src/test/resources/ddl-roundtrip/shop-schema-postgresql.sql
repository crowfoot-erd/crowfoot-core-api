-- v1.34 왕복 시험 자료 — PostgreSQL에서 가져오기·생성·배포·리버스가 보존해야 하는 항목을 고루 담는다
-- (문자열 기본값, 소수 초, 생성 컬럼, CHECK, 복합·DESC 인덱스, 유니크 인덱스, 코멘트, 복합 FK)
SET client_min_messages = warning;

CREATE TABLE members (
    id            BIGSERIAL     PRIMARY KEY,
    email         VARCHAR(320)  NOT NULL,
    role          VARCHAR(15)   NOT NULL DEFAULT 'USER',
    status        VARCHAR(15)   NOT NULL DEFAULT 'DEFAULT',
    nickname      VARCHAR(40)   NOT NULL DEFAULT 'it''s me',
    timezone      VARCHAR(40)   NOT NULL DEFAULT 'Asia/Seoul',
    point         INTEGER       NOT NULL DEFAULT 0,
    max_blogs     INTEGER       NULL,
    verified      BOOLEAN       NOT NULL DEFAULT false,
    email_hash    BYTEA         NULL,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_members_email UNIQUE (email),
    CONSTRAINT ck_members_max_blogs CHECK (max_blogs IS NULL OR max_blogs >= 0),
    CONSTRAINT ck_members_role CHECK (role IN ('USER', 'ADMIN'))
);
COMMENT ON TABLE members IS '회원';
COMMENT ON COLUMN members.email IS '이메일 주소(로그인 아이디), 소문자로 정규화해 저장한다';

CREATE TABLE orders (
    id            BIGSERIAL     PRIMARY KEY,
    member_id     BIGINT        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    order_no      VARCHAR(30)   NOT NULL,
    quantity      INTEGER       NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(12,2) NOT NULL,
    total_price   NUMERIC(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    memo          TEXT          NULL,
    ordered_at    TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_orders_order_no ON orders (order_no);
CREATE INDEX idx_orders_member_ordered ON orders (member_id, ordered_at DESC);

CREATE TABLE order_lines (
    order_id      BIGINT        NOT NULL,
    line_no       INTEGER       NOT NULL,
    product_code  CHAR(8)       NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE INDEX idx_order_lines_product ON order_lines USING btree (product_code);
