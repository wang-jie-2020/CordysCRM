package cn.cordys.crm.purchase.dto.request;

import cn.cordys.crm.approval.constants.ApprovalResourceUpdateType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PurchaseUpdateRequest {

    @NotBlank
    @Schema(description = "id")
    private String id;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "采购单名称")
    private String name;

    @Size(max = 32)
    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "金额")
    private BigDecimal amount;

    /**
     * 审批回退时置为 {@link ApprovalResourceUpdateType#APPROVAL}，切面据此放行，避免二次提审。
     */
    @Schema(description = "更新类型")
    private String updateType;

    @Schema(description = "变更说明")
    private String comment;
}
