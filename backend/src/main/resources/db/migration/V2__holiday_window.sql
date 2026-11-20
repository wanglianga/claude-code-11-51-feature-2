-- ============================================================
-- 移动节日同店窗口比较数据产品
-- 固定引用：营业日历版本 / 节日日历版本 / 门店资格版本 / 指标定义与运行 / 批次快照
-- ============================================================

-- ---------- 批次级覆盖小时（NULL 表示整日覆盖） ----------
ALTER TABLE source_batch ADD COLUMN covered_hours NUMERIC(5,2);

-- ---------- 门店日级覆盖时段修订（如某来源只覆盖部分小时） ----------
CREATE TABLE source_coverage_slot (
    id              BIGSERIAL PRIMARY KEY,
    source_type     VARCHAR(32) NOT NULL,          -- orders | refunds | store_hours | holidays
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE NOT NULL,
    covered_hours   NUMERIC(5,2) NOT NULL,
    expected_hours  NUMERIC(5,2),
    note            VARCHAR(256),
    UNIQUE (source_type, store_id, business_date)
);

-- ---------- 门店档案 ----------
CREATE TABLE store_profile (
    store_id        VARCHAR(32) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    region          VARCHAR(32) NOT NULL,
    city            VARCHAR(64),
    opened_on       DATE,
    closed_on       DATE,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',  -- active | closed | relocated
    address         VARCHAR(256),
    note            VARCHAR(256)
);

-- ---------- 门店营业制度版本（不同星期营业安排/周末制度变化） ----------
CREATE TABLE store_schedule_version (
    id              BIGSERIAL PRIMARY KEY,
    store_id        VARCHAR(32) NOT NULL,
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    pattern_json    JSONB NOT NULL,                -- {"MON":13,"TUE":13,...,"SUN":12}
    note            VARCHAR(256)
);
CREATE INDEX idx_schedule_store_date ON store_schedule_version(store_id, effective_from);

-- ---------- 门店事件（迁址、临时停业、缩短营业、营业制度变化） ----------
CREATE TABLE store_event (
    id              BIGSERIAL PRIMARY KEY,
    store_id        VARCHAR(32) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,          -- relocation | temp_closure | hours_reduction | regime_change
    from_date       DATE NOT NULL,
    to_date         DATE,
    reduced_hours   NUMERIC(5,2),                  -- hours_reduction 事件期间每日缩短后的营业小时
    note            VARCHAR(256)
);
CREATE INDEX idx_store_event_store_date ON store_event(store_id, from_date);

-- ---------- 门店资格版本（运行固定引用） ----------
CREATE TABLE store_eligibility_version (
    id              BIGSERIAL PRIMARY KEY,
    note            VARCHAR(256),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store_eligibility_entry (
    id              BIGSERIAL PRIMARY KEY,
    version_id      BIGINT NOT NULL REFERENCES store_eligibility_version(id),
    store_id        VARCHAR(32) NOT NULL,
    region          VARCHAR(32) NOT NULL,
    eligible_from   DATE,
    eligible_to     DATE,
    status          VARCHAR(16) NOT NULL DEFAULT 'eligible',  -- eligible | ineligible
    reason          VARCHAR(256),
    UNIQUE (version_id, store_id)
);

-- ---------- 门店营业日历版本（运行固定引用） ----------
CREATE TABLE store_calendar_version (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'approved',  -- draft | approved | superseded
    note            VARCHAR(256),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE store_calendar_day (
    id              BIGSERIAL PRIMARY KEY,
    version_id      BIGINT NOT NULL REFERENCES store_calendar_version(id),
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE NOT NULL,
    status          VARCHAR(16) NOT NULL,          -- open | closed | temp_closed | relocated
    open_hours      NUMERIC(5,2),
    note            VARCHAR(256),
    UNIQUE (version_id, store_id, business_date)
);
CREATE INDEX idx_store_calendar_day_lookup ON store_calendar_day(version_id, store_id, business_date);

-- ---------- 获批节日日历版本（移动节日日期登记 / 追认） ----------
CREATE TABLE holiday_calendar_version (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'approved',  -- draft | approved | superseded
    approved_by     VARCHAR(64),
    approved_at     TIMESTAMPTZ,
    supersedes_id   BIGINT,                        -- 追认时指向被替代版本
    note            VARCHAR(256),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE holiday_calendar_entry (
    id              BIGSERIAL PRIMARY KEY,
    version_id      BIGINT NOT NULL REFERENCES holiday_calendar_version(id),
    region          VARCHAR(32) NOT NULL,
    holiday_key     VARCHAR(32) NOT NULL,          -- 如 CNY / EASTER
    holiday_year    INTEGER NOT NULL,
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    name            VARCHAR(64) NOT NULL,
    approval_ref    VARCHAR(128),                  -- 地方获批文号
    approved        BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (version_id, region, holiday_key, holiday_year)
);
CREATE INDEX idx_holiday_entry_lookup ON holiday_calendar_entry(version_id, holiday_key, holiday_year, region);

-- ---------- 窗口比较运行（固定所有引用与批次快照） ----------
CREATE TABLE holiday_window_run (
    id                      BIGSERIAL PRIMARY KEY,
    product_key             VARCHAR(64) NOT NULL DEFAULT 'movable_holiday_same_store_window',
    holiday_key             VARCHAR(32) NOT NULL,
    base_year               INTEGER NOT NULL,
    compare_year            INTEGER NOT NULL,
    alignments              VARCHAR(64) NOT NULL,  -- fiscal_week | holiday_phase | fiscal_week,holiday_phase
    calendar_version_id     BIGINT NOT NULL,
    store_calendar_version_id BIGINT NOT NULL,
    eligibility_version_id  BIGINT NOT NULL,
    metric_key              VARCHAR(64) NOT NULL,
    metric_version_id       BIGINT NOT NULL,
    metric_run_id           BIGINT NOT NULL,
    batch_snapshot_json     JSONB NOT NULL,
    batch_snapshot_hash     VARCHAR(64),
    config_json             JSONB NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'computed',  -- computed | failed | released
    summary_json            JSONB,
    error                   TEXT,
    created_by              VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 逐店逐对齐路线结果 ----------
CREATE TABLE holiday_window_store_result (
    id                      BIGSERIAL PRIMARY KEY,
    run_id                  BIGINT NOT NULL REFERENCES holiday_window_run(id),
    alignment               VARCHAR(16) NOT NULL,   -- fiscal_week | holiday_phase
    store_id                VARCHAR(32) NOT NULL,
    region                  VARCHAR(32) NOT NULL,
    status                  VARCHAR(32) NOT NULL,   -- comparable | comparable_with_caveat | excluded
    reason                  VARCHAR(512),
    base_holiday_start      DATE,
    base_holiday_end        DATE,
    compare_holiday_start   DATE,
    compare_holiday_end     DATE,
    base_window_start       DATE,
    base_window_end         DATE,
    compare_window_start    DATE,
    compare_window_end      DATE,
    base_business_days      INTEGER,
    compare_business_days   INTEGER,
    base_closed_days        INTEGER,
    compare_closed_days     INTEGER,
    base_sales              NUMERIC(20,4),
    compare_sales           NUMERIC(20,4),
    change_abs              NUMERIC(20,4),
    change_ratio            NUMERIC(12,6),
    base_holiday_sales      NUMERIC(20,4),
    compare_holiday_sales   NUMERIC(20,4),
    gaps_json               JSONB,
    rebuild_json             JSONB,
    flags_json              JSONB,
    notes_json              JSONB,
    UNIQUE (run_id, alignment, store_id)
);
CREATE INDEX idx_hw_store_result_run ON holiday_window_store_result(run_id);

-- ---------- 运行发布（发布后不可改写，修订生成新运行） ----------
CREATE TABLE holiday_window_release (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL UNIQUE REFERENCES holiday_window_run(id),
    released_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by     VARCHAR(64),
    note            VARCHAR(512)
);
