import { useQuery } from '@tanstack/react-query';
import { resolveFileDisplayUrl } from '../api/question-files';
import { questionFileIdFromSrc } from '../utils/question-files';
import { queryKeys } from './keys';

/**
 * Hook hiển thị cho src lưu trong tài liệu: src không phải định danh bền
 * của hệ (URL ngoài, URL ký server đã phát sẵn) thì dùng nguyên; định danh
 * bền thì đổi sang URL ký và làm mới trước khi hết hạn (hạn ký 300 giây,
 * staleTime 240 giây).
 */
export function useResolvedFileSrc(src: string | undefined): string | undefined {
  const fileId = src ? questionFileIdFromSrc(src) : null;
  const query = useQuery({
    queryKey: queryKeys.questionFileSrc(fileId),
    queryFn: () => resolveFileDisplayUrl(fileId as string),
    enabled: fileId !== null,
    staleTime: 240_000,
  });
  if (!src || fileId === null) return src;
  return query.data ?? src;
}
