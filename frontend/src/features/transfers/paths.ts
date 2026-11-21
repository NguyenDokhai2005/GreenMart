import { APP_PATHS } from '../auth/routePolicy';

export const TRANSFERS_PATH = `${APP_PATHS.operations}/transfers`;
export const transferNewPath = `${TRANSFERS_PATH}/new`;
export const transferDetailPath = (id: number) => `${TRANSFERS_PATH}/${id}`;
export const transferEditPath = (id: number) => `${TRANSFERS_PATH}/${id}/edit`;
