/**
 * Các trạng thái hiển thị dùng chung của feature classroom (issue #102): đang tải, lỗi theo hợp đồng, rỗng, và
 * cổng phiên bọc mọi màn hình.
 *
 * Mọi màn hình của feature đều bắt đầu từ cùng một dãy trạng thái — phiên chưa sẵn sàng, chưa đăng nhập, lỗi tải
 * phiên, lỗi dữ kiện — nên các vỏ hiển thị đó nằm ở đây thay vì chép vào từng route. Thông điệp lỗi luôn đi qua
 * `displayErrorMessage` của tầng api để 403/404 hợp nhất một thông điệp như hợp đồng yêu cầu, bất kể màn hình
 * nào gặp lỗi.
 */
import type { ReactNode } from 'react';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import { displayErrorMessage } from '../api/errors';
import { requestLogin, type ClassroomSession } from '../hooks/use-session';

/**
 * Dòng trạng thái đang tải dữ kiện.
 *
 * @param props - Nhãn mô tả dữ kiện đang tải.
 * @returns Phần tử hiển thị trạng thái tải.
 */
export function LoadingNote({ label = 'Đang tải dữ kiện…' }: { label?: string }) {
  return (
    <p role="status" className="text-sm text-muted-foreground">
      {label}
    </p>
  );
}

/**
 * Khối báo lỗi đã ánh xạ theo hợp đồng, kèm nút thử lại khi thao tác có thể lặp lại an toàn.
 *
 * @param props - Lỗi gốc và hàm thử lại (không bắt buộc).
 * @returns Phần tử hiển thị thông điệp lỗi.
 */
export function ErrorNote({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div role="alert" className="flex flex-wrap items-center gap-3">
      <p className="text-sm text-destructive">{displayErrorMessage(error)}</p>
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" onClick={onRetry}>
          Thử lại
        </Button>
      ) : null}
    </div>
  );
}

/**
 * Dòng thông báo danh sách rỗng.
 *
 * @param props - Nội dung thông báo.
 * @returns Phần tử hiển thị trạng thái rỗng.
 */
export function EmptyNote({ children }: { children: ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>;
}

/**
 * Cổng phiên cho mọi màn hình của feature: chỉ khi phiên ở pha `ready` mới gọi hàm render con với dữ kiện phiên;
 * các pha còn lại hiển thị đúng một vỏ tương ứng (đang tải, mời đăng nhập giữ nguyên trang đang đứng, hoặc lỗi
 * tải phiên kèm thử lại bằng cách tải lại trang).
 *
 * @param props - Trạng thái phiên từ `useClassroomSession` và hàm render nội dung khi phiên sẵn sàng.
 * @returns Nội dung màn hình hoặc vỏ trạng thái tương ứng với pha phiên.
 */
export function SessionGate({
  session,
  children,
}: {
  session: ClassroomSession;
  children: (ready: Extract<ClassroomSession, { phase: 'ready' }>) => ReactNode;
}) {
  if (session.phase === 'ready') {
    return <>{children(session)}</>;
  }
  return (
    <PageShell width="medium">
      <Card>
        <CardHeader>
          <CardTitle>Giáo khoa</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col items-start gap-3">
          {session.phase === 'loading' ? <LoadingNote label="Đang tải phiên đăng nhập…" /> : null}
          {session.phase === 'anonymous' ? (
            <>
              <p className="text-sm text-muted-foreground">
                Hãy đăng nhập để dùng các màn hình dạy và học của trung tâm này.
              </p>
              <Button type="button" onClick={() => requestLogin()}>
                Đăng nhập
              </Button>
            </>
          ) : null}
          {session.phase === 'error' ? (
            <ErrorNote error={session.error} onRetry={() => window.location.reload()} />
          ) : null}
        </CardContent>
      </Card>
    </PageShell>
  );
}
