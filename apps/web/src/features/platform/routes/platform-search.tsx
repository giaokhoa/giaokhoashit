import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import '#config/i18n';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent } from '@lms/ui/components/card';
import { Empty, EmptyHeader, EmptyTitle } from '@lms/ui/components/empty';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { PageHeader, PageShell } from '#components/page-shell';
import { useQueryClient } from '@tanstack/react-query';
import { systemCapabilities, usePermissions } from '#lib/permissions';
import { globalSearchQueryOptions } from '../queries/search';
import { globalSearchTarget, type GlobalSearchDoc } from '#lib/search-target';

const AREA_LABEL_KEYS: Record<string, string> = {
  courses: 'searchAreaCourses',
  users: 'searchAreaUsers',
  contentbank: 'searchAreaContentBank',
};

export function SearchPage() {
  const { t: t } = useTranslation();
  const queryClient = useQueryClient();
  const perms = usePermissions();
  // Quyền quản trị người dùng của chính người đang tìm, suy từ ảnh chụp quyền: điều kiện duy nhất để kết quả `users`
  // gắn đích màn quản trị (màn đích tự gác `user:update`). Ảnh chụp chưa tải được thì coi như không có quyền — kết quả
  // giữ dạng thông tin, đúng chiều an toàn của ánh xạ.
  const canManageUsers =
    (perms.access?.isSiteAdmin ?? false) ||
    (perms.access ? systemCapabilities(perms.access).includes('user:update') : false);
  const [searchParams] = useSearchParams();
  const urlQ = searchParams.get('q');
  const [q, setQ] = useState('');
  const [results, setResults] = useState<GlobalSearchDoc[]>([]);
  const [searched, setSearched] = useState(false);
  // Chốt trình tự các lượt tìm: số thứ tự của lượt MỚI NHẤT còn hiệu lực. Mỗi lượt chạy lấy một số tăng dần; một lượt
  // chỉ được ghi kết quả khi số của nó vẫn là mới nhất lúc request trả về — lượt cũ về sau (mạng chậm) không ghi đè kết
  // quả của lượt mới hơn, và nhánh dọn trạng thái cũng tăng số này để vô hiệu hoá mọi lượt đang bay.
  const latestSearchRef = useRef(0);

  const runSearch = useCallback(async (keyword: string) => {
    const runId = ++latestSearchRef.current;
    const docs = await queryClient.fetchQuery(globalSearchQueryOptions(keyword));
    if (runId !== latestSearchRef.current) return;
    setResults(docs);
    setSearched(true);
  }, [queryClient]);

  useEffect(() => {
    if (urlQ === null) return;
    setQ(urlQ);
    if (urlQ.trim()) {
      void runSearch(urlQ);
    } else {
      latestSearchRef.current += 1;
      setResults([]);
      setSearched(false);
    }
  }, [urlQ, runSearch]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    await runSearch(q);
  }
  return (
    <PageShell width="narrow">
      <PageHeader title={t('searchTitle')} />
      <Card>
        <CardContent className="space-y-3">
          <form onSubmit={(e) => void submit(e)} className="flex gap-2">
            <Label htmlFor="search-q" className="sr-only">
              {t('searchQuery')}
            </Label>
            <Input
              id="search-q"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={t('searchPlaceholder')}
            />
            <Button type="submit">{t('searchButton')}</Button>
          </form>
          <ul aria-label={t('searchResults')} className="space-y-2">
            {results.map((d) => {
              const target = globalSearchTarget(d, { canManageUsers });
              const body = (
                <>
                  <p className="font-medium">{d.title}</p>
                  <p className="text-xs text-muted-foreground">
                    {AREA_LABEL_KEYS[d.area] ? t(AREA_LABEL_KEYS[d.area]) : d.area}
                  </p>
                  <p className="text-sm">{d.content}</p>
                </>
              );
              return (
                <li key={d.id}>
                  {target ? (
                    <Link to={target} className="block rounded border p-2 hover:bg-accent" data-testid={`search-result-${d.id}`}>
                      {body}
                    </Link>
                  ) : (
                    <div className="rounded border p-2">{body}</div>
                  )}
                </li>
              );
            })}
          </ul>
          {searched && results.length === 0 ? (
            <Empty className="p-6">
              <EmptyHeader>
                <EmptyTitle>{t('noResults')}</EmptyTitle>
              </EmptyHeader>
            </Empty>
          ) : null}
        </CardContent>
      </Card>
    </PageShell>
  );
}

export { SearchPage as default };

export { RouteErrorBoundary as ErrorBoundary } from '#components/route-error-boundary';
import { platformKeyMeta } from '#lib/route-meta';
export function meta() {
  return platformKeyMeta('searchTitle');
}
