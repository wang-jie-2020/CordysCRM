-- 采购单（审批接入验证用的最小实体）
CREATE TABLE purchase
(
    `id`              VARCHAR(32)  NOT NULL COMMENT 'id',
    `name`            VARCHAR(255) NOT NULL COMMENT '采购单名称',
    `owner`           VARCHAR(32)  NOT NULL COMMENT '负责人',
    `amount`          DECIMAL(20, 10) COMMENT '金额',
    `approval_status` VARCHAR(50)  NOT NULL DEFAULT 'NONE' COMMENT '审批状态',
    `approved`        TINYINT(1) DEFAULT 0 COMMENT '是否审批通过过',
    `organization_id` VARCHAR(32)  NOT NULL COMMENT '组织id',
    `create_time`     BIGINT       NOT NULL COMMENT '创建时间',
    `update_time`     BIGINT       NOT NULL COMMENT '更新时间',
    `create_user`     VARCHAR(32)  NOT NULL COMMENT '创建人',
    `update_user`     VARCHAR(32)  NOT NULL COMMENT '更新人',
    PRIMARY KEY (id)
) COMMENT = '采购单'
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci;

CREATE INDEX idx_purchase_owner ON purchase (owner ASC);
CREATE INDEX idx_purchase_organization_id ON purchase (organization_id ASC);
CREATE INDEX idx_purchase_approval_status ON purchase (approval_status);
