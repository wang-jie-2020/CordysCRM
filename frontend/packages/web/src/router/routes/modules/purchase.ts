import { PurchaseRouteEnum } from '@/enums/routeEnum';

import { DEFAULT_LAYOUT } from '../base';
import type { AppRouteRecordRaw } from '../types';

const purchase: AppRouteRecordRaw = {
  path: '/purchase',
  name: PurchaseRouteEnum.PURCHASE,
  redirect: '/purchase/index',
  component: DEFAULT_LAYOUT,
  meta: {
    locale: 'module.purchase',
    permissions: ['PURCHASE:READ'],
    icon: 'iconicon_order_form',
    hideChildrenInMenu: true,
    collapsedLocale: 'module.purchase',
  },
  children: [
    {
      path: 'index',
      name: PurchaseRouteEnum.PURCHASE_INDEX,
      component: () => import('@/views/purchase/index.vue'),
      meta: {
        locale: 'module.purchase',
        isTopMenu: true,
        permissions: ['PURCHASE:READ'],
      },
    },
  ],
};

export default purchase;
