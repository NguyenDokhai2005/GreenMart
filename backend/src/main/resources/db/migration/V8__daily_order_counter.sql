-- Bang dem so thu tu giao dich trong ngay cho moi chi nhanh
CREATE TABLE order_sequence (
    branch_id  BIGINT NOT NULL REFERENCES branch(id),
    order_date DATE   NOT NULL,
    seq        BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (branch_id, order_date)
);
