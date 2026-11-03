import { Navigate } from 'react-router';

export function SettingsIndexPage() {
  return <Navigate to="/settings/security" replace />;
}

export default SettingsIndexPage;

// Biên lỗi + tiêu đề theo route (cụm khung SSR #146, từ rà soát #133).
export { RouteErrorBoundary as ErrorBoundary } from '#components/route-error-boundary';
import { appKeyMeta } from '#lib/route-meta';
export function meta() {
  return appKeyMeta('navSettings');
}
