import { ProcessStatusEnum } from '@lib/shared/enums/process';

import type { ModuleField } from './common';

export interface SavePurchaseParams {
  name: string;
  owner?: string;
  amount?: number | string;
  moduleFields?: ModuleField[];
}

export interface UpdatePurchaseParams extends SavePurchaseParams {
  id: string;
  updateType?: string;
  comment?: string;
}

export interface PurchaseItem {
  id: string;
  name: string;
  owner: string;
  ownerName?: string;
  amount?: number;
  approvalStatus: ProcessStatusEnum;
  approved?: boolean;
  organizationId?: string;
  createTime?: number;
  updateTime?: number;
  moduleFields: ModuleField[];
}
