package cn.cordys.crm.purchase.dto;

import cn.cordys.common.domain.BaseModuleFieldValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购单详情。属性名即条件分支里的 fieldId（{@code ModuleFormService.compressResourceDetail} 按属性名转字段值）。
 */
@Data
public class PurchaseGetResponse {

    @Schema(description = "id")
    private String id;

    @Schema(description = "采购单名称")
    private String name;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "金额")
    private BigDecimal amount;

    @Schema(description = "审批状态")
    private String approvalStatus;

    @Schema(description = "组织id")
    private String organizationId;

    @Schema(description = "自定义字段值，本实体无自定义字段，固定为空集合")
    private List<BaseModuleFieldValue> moduleFields = List.of();
}
