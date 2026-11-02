package cn.cordys.crm.purchase.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class PurchasePageRequest {

    @Schema(description = "当前页码")
    private int current = 1;

    @Schema(description = "每页显示条数")
    private int pageSize = 30;

    @Schema(description = "名称关键字")
    private String keyword;
}
