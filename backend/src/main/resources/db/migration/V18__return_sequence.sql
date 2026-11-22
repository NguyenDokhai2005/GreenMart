-- Bảng đếm số thứ tự phiếu trả hàng trong ngày cho mỗi chi nhánh 
CREATE TABLE return_sequence (
    branch_id   BIGINT NOT NULL REFERENCES branch(id),
    return_date DATE   NOT NULL,
    seq         BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (branch_id, return_date)
);
