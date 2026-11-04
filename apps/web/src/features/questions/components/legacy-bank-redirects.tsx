import { Navigate, useLocation, useParams } from 'react-router';

/**
 * Lớp tương thích đường dẫn CŨ của cụm ngân hàng (chuẩn đường dẫn, issue #221 — mục 3.5 của hồ sơ chuẩn): URL cũ được
 * chuyển hướng THAY THẾ (replace) sang path mới tương ứng ngay khi khớp, để bookmark/liên kết đã chia sẻ của giáo viên
 * không gãy. Các query thuộc diện F2 (lọc/prefill) ngoài bank/section được giữ nguyên khi chuyển. Lớp tương thích giữ
 * tối thiểu 2 chu kỳ phát hành rồi gỡ ở lát R6 của hồ sơ. Nhóm tương thích của họ Kho học liệu nằm ở
 * `features/content-bank/components/legacy-content-bank-redirects` (tách khi tổ chức lại apps/web theo mô hình
 * features/, issue #500).
 */

export function legacyQuestionsTarget(search: string): string | null {
  const params = new URLSearchParams(search);
  const bank = params.get('bank');
  if (!bank) return null;
  const section = params.get('section');
  let target: string | null = null;
  if (bank === 'my-questions') {
    target = section === 'stimuli' ? '/questions/stimuli' : '/questions';
  } else if (bank === 'my-papers') {
    target = '/exams';
  }
  if (!target) return null;
  params.delete('bank');
  params.delete('section');
  const rest = params.toString();
  return rest ? `${target}?${rest}` : target;
}

export function SystemBankIndexRedirect() {
  return <Navigate to="/system-bank/questions" replace />;
}

export function LegacyMatrixNewRedirect() {
  return <Navigate to="/matrices/new" replace />;
}

export function LegacyMatrixEditRedirect() {
  const { matrixId } = useParams();
  return <Navigate to={`/matrices/${matrixId}/edit`} replace />;
}

export function LegacyExamPapersRedirect() {
  const location = useLocation();
  return <Navigate to={`/exams${location.search}`} replace />;
}

export function LegacyExamPaperNewRedirect() {
  const location = useLocation();
  return <Navigate to={`/exams/new${location.search}`} replace />;
}

export function LegacyExamPaperEditRedirect() {
  const { paperId } = useParams();
  const location = useLocation();
  return <Navigate to={`/exams/${paperId}/edit${location.search}`} replace />;
}

export function LegacySystemBankPapersRedirect() {
  return <Navigate to="/system-bank/exams" replace />;
}
