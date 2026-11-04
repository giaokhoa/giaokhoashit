/**
 * Khối tệp bài làm trên màn hình làm bài (issue #121).
 *
 * Flow: khối đọc danh sách tệp của lượt qua hook `useSubmissionFiles` — trạng thái của từng tệp là dữ kiện
 * server đã chốt, khối chỉ trình bày đúng trạng thái đó. Học viên gắn một phiên tải lên của nền tệp bằng mã
 * phiên và gỡ tệp khi lượt còn đang làm; cả hai thao tác xong đều vô hiệu hoá danh sách để đọc lại bản server
 * giữ. Bất biến hiển thị theo hợp đồng tệp bài làm: chỉ tệp mang trạng thái `ready` (cờ `submitted`) mới được
 * trình bày là đã nộp; tệp đang tải/đang xử lý luôn ghi rõ chưa tính là đã nộp, kể cả khi byte đã truyền đủ.
 */
import { useState } from 'react';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { useAttachSubmissionFile, useRemoveSubmissionFile, useSubmissionFiles } from '../queries/hooks';
import type { SubmissionFileView } from '../types/server';
import { ErrorNote, LoadingNote } from './states';

/**
 * Dòng trạng thái của một tệp bài làm theo đúng trạng thái server trả: chỉ `ready` mới là đã chốt và tính là
 * đã nộp; mọi trạng thái còn lại đều nêu rõ tệp chưa được tính là đã nộp hoặc đã bị từ chối/cách ly.
 *
 * @param file - Tệp bài làm theo hình chiếu của server.
 * @returns Chuỗi trạng thái hiển thị cho tệp đó.
 */
export function submissionFileStatusText(file: SubmissionFileView): string {
  switch (file.status) {
    case 'ready':
      return 'Đã chốt — tính là tệp đã nộp';
    case 'uploading':
      return 'Đang tải — chưa tính là đã nộp';
    case 'processing':
      return 'Đang kiểm tra/xử lý — chưa tính là đã nộp';
    case 'rejected':
      return 'Bị từ chối — không tính là đã nộp';
    case 'quarantined':
      return 'Đã cách ly — không tính là đã nộp';
    default:
      return 'Chưa rõ trạng thái — chưa tính là đã nộp';
  }
}

/**
 * Khối tệp bài làm của một lượt làm: danh sách tệp kèm trạng thái đã chốt, ô gắn phiên tải lên và nút gỡ khi
 * lượt chưa khoá.
 *
 * Side-effect: đọc `GET /api/attempts/{id}/files`; gắn gửi `POST /api/attempts/{id}/files`, gỡ gửi
 * `DELETE /api/attempts/{id}/files/{fileId}` qua các mutation của feature.
 *
 * @param props - Định danh lượt làm và cờ khoá (lượt đã nộp thì chỉ đọc).
 * @returns Phần tử khối tệp bài làm.
 */
export function SubmissionFilesPanel({ attemptId, locked }: { attemptId: string; locked: boolean }) {
  const filesQuery = useSubmissionFiles(attemptId);
  const attachFile = useAttachSubmissionFile();
  const removeFile = useRemoveSubmissionFile();
  const [sessionId, setSessionId] = useState('');

  const onAttach = () => {
    const trimmed = sessionId.trim();
    if (!trimmed) return;
    attachFile.mutate({ attemptId, uploadSessionId: trimmed }, { onSuccess: () => setSessionId('') });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Tệp bài làm</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {filesQuery.isPending ? <LoadingNote label="Đang tải tệp bài làm…" /> : null}
        {filesQuery.isError ? <ErrorNote error={filesQuery.error} onRetry={() => void filesQuery.refetch()} /> : null}
        {filesQuery.data && filesQuery.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">Chưa có tệp bài làm nào.</p>
        ) : null}
        {filesQuery.data && filesQuery.data.length > 0 ? (
          <ul className="flex flex-col gap-2">
            {filesQuery.data.map((file) => (
              <li key={file.id} className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{file.displayName}</span>
                <span className="text-sm text-muted-foreground">{submissionFileStatusText(file)}</span>
                {!locked ? (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={removeFile.isPending}
                    onClick={() => removeFile.mutate({ attemptId, fileId: file.id })}
                  >
                    Gỡ tệp
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : null}
        {!locked ? (
          <div className="flex flex-col gap-2">
            <Label htmlFor="submission-file-session">Mã phiên tải lên</Label>
            <div className="flex flex-wrap gap-2">
              <Input
                id="submission-file-session"
                value={sessionId}
                onChange={(event) => setSessionId(event.target.value)}
                placeholder="Dán mã phiên tải lên của tệp"
              />
              <Button type="button" disabled={attachFile.isPending || !sessionId.trim()} onClick={onAttach}>
                Gắn tệp vào bài
              </Button>
            </div>
            {attachFile.isError ? <ErrorNote error={attachFile.error} /> : null}
            {removeFile.isError ? <ErrorNote error={removeFile.error} /> : null}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
