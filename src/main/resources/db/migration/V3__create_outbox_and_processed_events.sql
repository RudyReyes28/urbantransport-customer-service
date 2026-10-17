CREATE TABLE outbox_events (
                               id             UUID         PRIMARY KEY,
                               aggregate_type VARCHAR(50)  NOT NULL,
                               aggregate_id   UUID         NOT NULL,
                               event_type     VARCHAR(60)  NOT NULL,
                               payload        TEXT         NOT NULL,
                               created_at     TIMESTAMP    NOT NULL DEFAULT now(),
                               published_at   TIMESTAMP
);

CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
                                  event_id     UUID      PRIMARY KEY,
                                  processed_at TIMESTAMP NOT NULL DEFAULT now()
);