import type { CordysAxios } from '@lib/shared/api/http/Axios';
import {
  AddPurchaseUrl,
  DeletePurchaseUrl,
  GetPurchaseDetailUrl,
  PurchaseFormConfigUrl,
  PurchasePageUrl,
  UpdatePurchaseUrl,
} from '@lib/shared/api/requrls/purchase';
import type { CommonList } from '@lib/shared/models/common';
import type { PurchaseItem, SavePurchaseParams, UpdatePurchaseParams } from '@lib/shared/models/purchase';
import type { FormDesignConfigDetailParams } from '@lib/shared/models/system/module';

export default function usePurchaseApi(CDR: CordysAxios) {
  function getPurchaseList(data: { current: number; pageSize: number; keyword?: string }) {
    return CDR.post<CommonList<PurchaseItem>>({ url: PurchasePageUrl, data });
  }

  function getPurchaseDetail(id: string, approvalTaskId?: string, commentMentionId?: string) {
    return CDR.get<PurchaseItem>({ url: `${GetPurchaseDetailUrl}/${id}`, params: { approvalTaskId, commentMentionId } });
  }

  function getPurchaseFormConfig() {
    return CDR.get<FormDesignConfigDetailParams>({ url: PurchaseFormConfigUrl });
  }

  function addPurchase(data: SavePurchaseParams) {
    return CDR.post({ url: AddPurchaseUrl, data });
  }

  function updatePurchase(data: UpdatePurchaseParams) {
    return CDR.post({ url: UpdatePurchaseUrl, data });
  }

  function deletePurchase(id: string) {
    return CDR.get({ url: `${DeletePurchaseUrl}/${id}` });
  }

  return {
    getPurchaseList,
    getPurchaseDetail,
    getPurchaseFormConfig,
    addPurchase,
    updatePurchase,
    deletePurchase,
  };
}
