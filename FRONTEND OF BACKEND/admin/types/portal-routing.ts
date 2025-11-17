export type PlatformRole =
  | 'ROLE_ADMIN'
  | 'ROLE_ACCOUNTING'
  | 'ROLE_FACTORY'
  | 'ROLE_SALES'
  | 'ROLE_DEALER';

const hasRole = (roles: readonly string[], role: PlatformRole) =>
  roles.map((entry) => entry.toUpperCase()).includes(role);

export const hasAccountingPortal = (roles: readonly string[]) =>
  hasRole(roles, 'ROLE_ADMIN') || hasRole(roles, 'ROLE_ACCOUNTING');

export const hasFactoryPortal = (roles: readonly string[]) =>
  hasRole(roles, 'ROLE_ADMIN') || hasRole(roles, 'ROLE_FACTORY');

export const hasSalesPortal = (roles: readonly string[]) =>
  hasRole(roles, 'ROLE_ADMIN') || hasRole(roles, 'ROLE_SALES');

export const hasDealerPortal = (roles: readonly string[]) =>
  hasRole(roles, 'ROLE_ADMIN') || hasRole(roles, 'ROLE_DEALER');
