import { type RouteConfig, relative } from '@react-router/dev/routes';

const { prefix, route } = relative(import.meta.dirname);

export default prefix('settings', [
  route('', 'routes/settings-index.tsx'),
  route('security', 'routes/settings-security.tsx'),
  route('linked-accounts', 'routes/settings-linked-accounts.tsx'),
  route('notifications', 'routes/settings-notifications.tsx'),
]) satisfies RouteConfig;
