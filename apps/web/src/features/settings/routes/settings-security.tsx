import { SessionsCard, TwoFactorCard } from '../components/profile-cards';
import { SettingsShell } from '../components/settings-shell';

export function SettingsSecurityPage() {
  return (
    <SettingsShell>
      <TwoFactorCard />
      <SessionsCard />
    </SettingsShell>
  );
}

export default SettingsSecurityPage;

// Biên lỗi + tiêu đề theo route (cụm khung SSR #146, từ rà soát #133).
export { RouteErrorBoundary as ErrorBoundary } from '#components/route-error-boundary';
import { appKeyMeta } from '#lib/route-meta';
export function meta() {
  return appKeyMeta('settingsSecurity');
}
