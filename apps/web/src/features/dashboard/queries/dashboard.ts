import { queryOptions, useQuery } from '@tanstack/react-query';
import { fetchDashboardOverview } from '../api/dashboard';
import { queryKeys } from './keys';

export function dashboardOverviewQueryOptions() {
  return queryOptions({
    queryKey: queryKeys.dashboardOverview,
    queryFn: () => fetchDashboardOverview(),
  });
}

export function useDashboardOverview() {
  return useQuery(dashboardOverviewQueryOptions());
}
