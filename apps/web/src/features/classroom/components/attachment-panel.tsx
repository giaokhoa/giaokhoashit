/**
 * Khung tệp đính kèm của một phiên bản câu hỏi (issue #119).
 *
 * Flow: giáo viên chọn ảnh và vị trí gắn → mở phiên tải lên → tải byte lên để server kiểm tra nội dung thật
 * → xem trước đúng bản ảnh đã xử lý mà server giữ (kèm checksum của bản đó) → xác nhận gắn vào bản nháp.
 * Mọi ảnh hiển thị ở đây đều tải bằng request có xác thực rồi hiển thị qua blob URL; blob URL chỉ là dữ
 * kiện đã được cấp trong phiên trình duyệt này, không phải quyền truy cập, và access token không nằm trong
 * bất kỳ URL nào. URL đối tượng được thu hồi khi thay ảnh xem trước hoặc rời màn hình để ảnh của một tài
 * khoản không còn nằm lại trong bộ nhớ của phiên trình duyệt dùng chung.
 */
import { useEffect, useRef, useState } from 'react';
import { Button } from '@lms/ui/components/button';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { NativeSelect } from '@lms/ui/components/native-select';
import {
  fetchAttachmentContent,
  fetchAttachmentPreview,
  openAttachmentSession,
  uploadAttachmentContent,
} from '../api/client';
import { displayErrorMessage } from '../api/errors';
import { useAttachments, useCompleteAttachmentSession } from '../queries/hooks';
import type { AttachmentLinkView, AttachmentPosition, QuestionVersionView, UploadSessionView } from '../types/server';
import { ATTACHMENT_ACCEPT, earlyAttachmentError } from '../utils/attachments';
import { EmptyNote, ErrorNote, LoadingNote } from './states';

const POSITION_LABELS: Record<AttachmentPosition, string> = {
  content: 'Nội dung câu',
  choice: 'Lựa chọn',
  answer: 'Đáp án',
  solution: 'Lời giải',
  document: 'Tài liệu',
};

/**
 * Khung tệp đính kèm: danh sách tệp đã gắn của phiên bản (mỗi tệp là một ảnh tải có xác thực) và, khi phiên
 * bản còn là bản nháp, luồng tải ảnh mới lên kèm xem trước và xác nhận gắn.
 *
 * @param props - Định danh câu hỏi và phiên bản đang xem.
 * @returns Phần tử khung tệp đính kèm.
 */
export function AttachmentPanel({ questionId, version }: { questionId: string; version: QuestionVersionView }) {
  const versionId = version.versionId;
  const isLocked = version.lockedAt !== null;
  const attachments = useAttachments(questionId, versionId);
  return (
    <section className="flex flex-col gap-3 border-t pt-4">
      <h3 className="text-sm font-medium">Tệp đính kèm</h3>
      {attachments.isPending ? <LoadingNote label="Đang tải tệp đính kèm…" /> : null}
      {attachments.isError ? (
        <ErrorNote error={attachments.error} onRetry={() => void attachments.refetch()} />
      ) : null}
      {attachments.data && attachments.data.length === 0 ? <EmptyNote>Chưa có tệp đính kèm nào.</EmptyNote> : null}
      {attachments.data && attachments.data.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {attachments.data.map((link) => (
            <li key={link.linkId} className="flex items-start gap-3">
              <AttachmentImage questionId={questionId} versionId={versionId} link={link} />
              <span className="text-sm">
                <span className="font-medium">{link.displayName}</span> — {POSITION_LABELS[link.position]}
                <br />
                <span className="text-muted-foreground">
                  {link.contentType}, {link.sizeBytes} byte, checksum {link.checksumSha256.slice(0, 12)}…
                </span>
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      {!isLocked ? <AttachmentUpload questionId={questionId} versionId={versionId} /> : null}
    </section>
  );
}

/**
 * Một ảnh đính kèm đã gắn: byte tải qua đường có xác thực tại thời điểm hiển thị và hiển thị bằng blob URL;
 * URL được thu hồi khi component rời khỏi màn hình.
 *
 * @param props - Định danh câu, phiên bản và liên kết tệp.
 * @returns Phần tử ảnh hoặc dòng trạng thái đang tải.
 */
function AttachmentImage({
  questionId,
  versionId,
  link,
}: {
  questionId: string;
  versionId: string;
  link: AttachmentLinkView;
}) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    fetchAttachmentContent(questionId, versionId, link.linkId)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => setUrl(null));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [questionId, versionId, link.linkId]);
  if (!url) return <span className="text-sm text-muted-foreground">Đang tải ảnh…</span>;
  return <img src={url} alt={link.displayName} className="max-h-40 rounded border object-contain" />;
}

/**
 * Luồng tải một ảnh mới lên bản nháp: chọn tệp và vị trí, tải lên để server kiểm tra, xem trước đúng bản
 * đã xử lý kèm checksum, rồi xác nhận gắn. Lỗi kiểm tra sớm (dung lượng, họ tệp) hiện ngay tại chỗ mà không
 * gọi API; lỗi của server hiển thị qua phép ánh xạ lỗi dùng chung của feature.
 *
 * @param props - Định danh câu và phiên bản nháp đích.
 * @returns Phần tử luồng tải lên.
 */
function AttachmentUpload({ questionId, versionId }: { questionId: string; versionId: string }) {
  const complete = useCompleteAttachmentSession();
  const [position, setPosition] = useState<AttachmentPosition>('content');
  const [file, setFile] = useState<File | null>(null);
  const [session, setSession] = useState<UploadSessionView | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const previewUrlRef = useRef<string | null>(null);

  useEffect(
    () => () => {
      if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    },
    [],
  );

  const replacePreview = (url: string | null) => {
    if (previewUrlRef.current) URL.revokeObjectURL(previewUrlRef.current);
    previewUrlRef.current = url;
    setPreviewUrl(url);
  };

  const onUpload = async () => {
    if (!file) return;
    const early = earlyAttachmentError(file);
    if (early) {
      setError(early);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const opened = await openAttachmentSession(questionId, versionId, { position, displayName: file.name });
      const uploaded = await uploadAttachmentContent(questionId, versionId, opened.sessionId, file);
      const blob = await fetchAttachmentPreview(questionId, versionId, opened.sessionId);
      replacePreview(URL.createObjectURL(blob));
      setSession(uploaded);
    } catch (caught) {
      setError(displayErrorMessage(caught));
    } finally {
      setBusy(false);
    }
  };

  const onComplete = async () => {
    if (!session) return;
    setError(null);
    try {
      await complete.mutateAsync({ questionId, versionId, sessionId: session.sessionId });
      setSession(null);
      setFile(null);
      replacePreview(null);
    } catch (caught) {
      setError(displayErrorMessage(caught));
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <Label htmlFor="attachment-position">Vị trí gắn</Label>
          <NativeSelect
            id="attachment-position"
            value={position}
            onChange={(event) => setPosition(event.target.value as AttachmentPosition)}
          >
            {(Object.keys(POSITION_LABELS) as AttachmentPosition[]).map((value) => (
              <option key={value} value={value}>
                {POSITION_LABELS[value]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="attachment-file">Chọn ảnh (PNG, JPEG hoặc WebP, tối đa 5 MiB)</Label>
          <Input
            id="attachment-file"
            type="file"
            accept={ATTACHMENT_ACCEPT}
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setSession(null);
              replacePreview(null);
              setError(null);
            }}
          />
        </div>
        <Button type="button" disabled={!file || busy || session !== null} onClick={() => void onUpload()}>
          {busy ? 'Đang tải lên…' : 'Tải ảnh lên'}
        </Button>
      </div>
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {session && previewUrl ? (
        <div className="flex flex-col gap-2">
          <p className="text-sm">
            Ảnh đã qua kiểm tra — checksum của bản sẽ gắn: {session.checksumSha256}
          </p>
          <img src={previewUrl} alt={session.displayName} className="max-h-52 rounded border object-contain" />
          <div>
            <Button type="button" disabled={complete.isPending} onClick={() => void onComplete()}>
              Gắn vào bản nháp
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
