package cn.cordys.crm.purchase.domain;

import cn.cordys.common.domain.BaseModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Table(name = "purchase")
public class Purchase extends BaseModel {

    @Schema(description = "采购单名称")
    private String name;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "采购明细 JSON")
    private String lines;

    @Schema(description = "审批状态")
    private String approvalStatus;

    @Schema(description = "是否审批通过过")
    private Boolean approved;

    @Schema(description = "组织id")
    private String organizationId;
}
