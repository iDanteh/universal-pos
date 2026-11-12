-- ============================================================
-- POS PLATFORM - V14 cash register core (ADR-017)
--
-- cash_session : the drawer (caja) of a tenant. A shift starts with an
--                OPENING FLOAT (fondo inicial), lives OPEN while sales are
--                collected, and ends CLOSED with a per-method COUNT.
--                Reconciliation is CASH-ONLY in this phase: the difference
--                of the drawer is counted_cash - expected_cash, where
--                expected = opening_float + SUM(CASH payments of the sales
--                attached to the session). Card/transfer/other counts are
--                recorded as information (they reconcile against the bank
--                statement, not against the drawer).
-- sale         : gains cash_session_id — the ATTACH of the sale to the
--                active shift at collection time. Composite FK
--                (cash_session_id, tenant_id) -> cash_session (id, tenant_id)
--                follows the V10/V11/V12 pattern; a sale created WITHOUT an
--                open session stays NULL (never force-assigned).
--
-- Integrity: partial UNIQUE index keeps ONE session OPEN per tenant
-- (one drawer, one shift). RLS: tenant_isolation, same contract as
-- V7/V10/V11/V12/V13. Lifecycle machine lives in the SERVICE (ADR-006 rule 3
-- pattern): OPEN -> CLOSED (+409 on any other transition). Outbox seam:
-- cash.session.opened / cash.session.closed (same contract as sales.*).
-- Devoluciones / notas de crédito contra cierre: fase 8.
-- ============================================================

CREATE TABLE cash_session (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES organization (id),
    -- the cashier who opened the drawer (app_user is global, no tenant_id)
    opened_by        uuid NOT NULL REFERENCES app_user (id),
    opening_float    numeric(14, 2) NOT NULL DEFAULT 0 CHECK (opening_float >= 0),
    status           text NOT NULL DEFAULT 'OPEN'
                     CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at        timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz,
    -- per-method count registered AT CLOSING (NULL while OPEN)
    counted_cash     numeric(14, 2) CHECK (counted_cash >= 0),
    counted_card     numeric(14, 2) CHECK (counted_card >= 0),
    counted_transfer numeric(14, 2) CHECK (counted_transfer >= 0),
    counted_other    numeric(14, 2) CHECK (counted_other >= 0),
    -- reconciliation (CASH-only expected; difference may be negative)
    expected_cash    numeric(14, 2),
    difference       numeric(14, 2),
    close_note       varchar(280),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

-- ONE drawer OPEN per tenant: the partial index IS the guard (same idiom as
-- the V13 active-invoice indexes), enforced at the DB, not in the service.
CREATE UNIQUE INDEX uq_cash_session_open
    ON cash_session (tenant_id)
    WHERE status = 'OPEN';

CREATE INDEX idx_cash_session_tenant_opened
    ON cash_session (tenant_id, opened_at DESC);

-- ---- Sale -> shift attach (only when the tenant had a drawer open) ----
ALTER TABLE sale ADD COLUMN cash_session_id uuid;

ALTER TABLE sale ADD CONSTRAINT fk_sale_cash_session
    FOREIGN KEY (cash_session_id, tenant_id)
    REFERENCES cash_session (id, tenant_id);

CREATE INDEX idx_sale_tenant_cash_session
    ON sale (tenant_id, cash_session_id);

-- ---- RLS (same contract as V7/V11/V13) ----
ALTER TABLE cash_session ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON cash_session
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- ---- Privilege sweep (sale already granted in V11) ----
GRANT SELECT, INSERT, UPDATE, DELETE
    ON cash_session
    TO posplatform_app;
