ALTER TABLE purchase
    ADD COLUMN lines TEXT NULL COMMENT '采购明细 JSON。详情属性名 lines，行内数量 key 为 qty';
