import type { ExamMatrixView, MatrixImportPreview } from '@lms/contracts';
import { apiFetch, apiJson } from '#lib/api';
import type {
  AvailabilityLite,
  BankLite,
  CurriculumLite,
  MatrixLite,
  MatrixSpecificationLite,
  MatrixTreeNode,
  SubjectLite,
} from '../types/matrix';

async function probe<T>(path: string): Promise<T | null> {
  try {
    return await apiJson<T>(path);
  } catch {
    return null;
  }
}

export function fetchMatrixBanks() {
  return apiJson<BankLite[]>('/api/v1/question-banks?kind=matrix');
}

/**
 * Đọc danh sách môn học chương trình (GET /curriculum/subjects). Kèm `forCourseId` thì API mở nhánh `course:update` tại
 * chính khoá đó cho giáo viên không có `curriculum:view` tại context hệ thống (điểm nghẽn D-3 của issue #233).
 */
export function fetchCurriculumSubjects(forCourseId?: string) {
  const frame = forCourseId ? `?forCourseId=${forCourseId}` : '';
  return apiJson<SubjectLite[]>(`/api/v1/curriculum/subjects${frame}`);
}

export function fetchCurriculaBySubject(subjectId: string, forCourseId?: string) {
  const frame = forCourseId ? `&forCourseId=${forCourseId}` : '';
  return apiJson<CurriculumLite[]>(`/api/v1/curriculum/curricula?subjectId=${subjectId}${frame}`);
}

export function fetchMatrixDetail(matrixId: string) {
  return apiJson<MatrixLite>(`/api/v1/exam-matrices/${matrixId}`);
}

export function fetchCurriculumTree(curriculumId: string) {
  return probe<MatrixTreeNode[]>(`/api/v1/curriculum/curricula/${curriculumId}/tree`);
}

export function fetchMatrixAvailability(matrixId: string) {
  return probe<AvailabilityLite>(`/api/v1/exam-matrices/${matrixId}/availability`);
}

export function fetchMatrixSpecification(matrixId: string) {
  return apiJson<MatrixSpecificationLite>(`/api/v1/exam-matrices/${matrixId}/specification`);
}

export async function downloadMatrixSpecification(matrixId: string, format: 'xlsx' | 'docx'): Promise<void> {
  const res = await apiFetch(`/api/v1/exam-matrices/${matrixId}/specification/export?format=${format}`);
  if (!res.ok) throw new Error(String(res.status));
  const blob = await res.blob();
  // Tên file ưu tiên lấy nguyên từ header của server (nguồn sự thật về tên bản xuất); chỉ khi header vắng/sai dạng mới
  // tự dựng tên dự phòng theo cùng quy ước để lượt tải vẫn chạy được.
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const filename = /filename="([^"]+)"/.exec(disposition)?.[1] ?? `ban-dac-ta-${matrixId.slice(0, 8)}.${format}`;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function createMatrix(body: {
  name: string;
  subjectId: string;
  curriculumId: string;
  bankId: string;
  levelSet: 'three' | 'four';
  totalScore: number;
  isTemplate: boolean;
  status: string;
}) {
  return apiJson<MatrixLite>('/api/v1/exam-matrices', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateMatrix(
  matrixId: string,
  body: { name: string; status: string; isTemplate: boolean; totalScore: number; levelSet?: 'three' | 'four' },
) {
  return apiJson(`/api/v1/exam-matrices/${matrixId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

export function addMatrixCell(
  matrixId: string,
  body: {
    curriculumNodeId: string;
    cognitiveLevel: string;
    qtype: string;
    questionCount: number;
    cellScore: number;
  },
) {
  return apiJson(`/api/v1/exam-matrices/${matrixId}/cells`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function deleteMatrixCell(matrixId: string, cellId: string) {
  return apiJson(`/api/v1/exam-matrices/${matrixId}/cells/${cellId}`, { method: 'DELETE' });
}

export async function downloadMatrixImportTemplate(input: {
  subjectId: string;
  curriculumId: string;
  includeAdvanced: boolean;
}): Promise<void> {
  const params = new URLSearchParams({
    subjectId: input.subjectId,
    curriculumId: input.curriculumId,
    levelSet: input.includeAdvanced ? 'four' : 'three',
  });
  const res = await apiFetch(`/api/v1/exam-matrices/template?${params.toString()}`);
  if (!res.ok) throw new Error(String(res.status));
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const filename =
    /filename="([^"]+)"/.exec(disposition)?.[1] ?? `mau-ma-tran-${input.subjectId.slice(0, 8)}.xlsx`;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * Xem trước file nhập ma trận (bước 3 của wizard I-3).
 *
 * Gửi file trong multipart trường `file` (để trình duyệt tự đặt Content-Type kèm boundary — ép JSON sẽ hỏng tải lên).
 * Server luôn trả 200 kể cả khi file sai khuôn: lỗi nằm trong payload (`valid=false` + `errors[]` định vị theo
 * sheet/dòng/cột), web hiển thị bảng lỗi đó và khoá nút xác nhận — không tự kết luận hợp lệ từ status HTTP.
 *
 * @param file - File .xlsx giáo viên đã điền (đúng file mẫu hệ sinh).
 * @returns Kết quả xem trước `MatrixImportPreview` của contracts.
 */
export async function previewMatrixImport(file: File): Promise<MatrixImportPreview> {
  const form = new FormData();
  form.append('file', file);
  return apiJson<MatrixImportPreview>('/api/v1/exam-matrices/import/preview', { method: 'POST', body: form });
}

/**
 * Ghi ma trận từ file đã xem trước (bước 4 của wizard I-3).
 *
 * Gửi lại CHÍNH file đã xem trước (không gửi kèm kết quả xem trước): server parse lại từ đầu tại thời điểm ghi — file
 * bị sửa tay sau xem trước mà vi phạm luật thì server từ chối 422 `quiz.matrix_import_invalid` kèm `matrixImportIssues`
 * định vị và KHÔNG ghi một phần; gửi lại cùng nội dung thì server trả ma trận đã có thay vì tạo bản thứ hai (chống ghi
 * trùng của I-2).
 *
 * @param file - Cùng file .xlsx đã qua bước xem trước.
 * @returns View ma trận đã ghi (hoặc bản đã có khi trùng nội dung).
 * @throws ApiError 422 kèm `problem.matrixImportIssues` khi file không còn hợp lệ tại thời điểm ghi; 403 khi thiếu
 *   quyền tại môn.
 */
export async function commitMatrixImport(file: File): Promise<ExamMatrixView> {
  const form = new FormData();
  form.append('file', file);
  return apiJson<ExamMatrixView>('/api/v1/exam-matrices/import', { method: 'POST', body: form });
}
