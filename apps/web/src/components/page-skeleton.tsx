/**
 * Khuôn trạng thái tải dùng chung ở tầng ứng dụng, dựng trên nguyên tử `Skeleton` của `@lms/ui`.
 *
 * Trước đây mỗi trang tự lắp trạng thái tải theo cùng một khuôn: một khung chứa mang nhãn aria và một danh sách các
 * khối Skeleton con xếp dọc. Component này là nơi duy nhất lắp khuôn khung đó; các trang chỉ truyền hình học của từng
 * dòng giữ chỗ (class của từng khối), class của khung và các thuộc tính aria/testid — hình thức trạng thái tải tại
 * từng trang giữ nguyên như khi tự lắp tại chỗ.
 *
 * Ranh giới: các vị trí chỉ gồm MỘT khối Skeleton đơn lẻ tại chỗ trong một khối con (không có khung và danh sách đi
 * kèm) không thuộc khuôn này — chúng dùng thẳng nguyên tử `Skeleton` vốn đã là thành phần dùng chung của `@lms/ui`.
 */
import { Skeleton } from '@lms/ui/components/skeleton';

export interface PageSkeletonProps {
  rows: string[];
  className?: string;
  label?: string;
  testId?: string;
  ariaHidden?: boolean;
}

export function PageSkeleton({ rows, className = 'space-y-2', label, testId, ariaHidden }: PageSkeletonProps) {
  return (
    <div className={className} aria-label={label} data-testid={testId} aria-hidden={ariaHidden || undefined}>
      {rows.map((rowClassName, index) => (
        <Skeleton key={index} className={rowClassName} />
      ))}
    </div>
  );
}
