/**
 * Khối báo lỗi/thông báo dùng chung ở tầng ứng dụng, dựng trên nguyên tử `Alert` của `@lms/ui`.
 *
 * Trước đây mọi trang tự lắp lại cùng một khuôn khối: một `Alert` (variant `destructive` hoặc mặc định), tuỳ chọn một
 * `AlertTitle`, và đúng một `AlertDescription` chứa nội dung — rà soát toàn bộ giao diện cho thấy mọi khối báo lỗi đang
 * có đều thuộc đúng khuôn này. Component này là nơi duy nhất lắp khuôn đó; các trang chỉ truyền nội dung và biến thể,
 * hình thức render giữ nguyên như khi tự lắp tại chỗ.
 */
import type { ReactNode } from 'react';

import { Alert, AlertDescription, AlertTitle } from '@lms/ui/components/alert';

export interface ErrorAlertProps {
  title?: ReactNode;
  /**
   * Biến thể của khối: `destructive` cho lỗi (phần lớn khối trong hệ), `default` cho thông báo trung tính. Bỏ qua thì
   * giữ biến thể mặc định của nguyên tử Alert, đúng như khối gốc không ghi variant.
   */
  variant?: 'default' | 'destructive';
  className?: string;
  descriptionClassName?: string;
  /** Vai trò ARIA ghi đè trên phần tử Alert (khối gốc có nơi đặt `status`); bỏ qua thì giữ `alert` của nguyên tử. */
  role?: string;
  children?: ReactNode;
}

export function ErrorAlert({ title, variant, className, descriptionClassName, role, children }: ErrorAlertProps) {
  return (
    // role chỉ được truyền xuống khi trang gọi có đặt: nguyên tử Alert tự đặt role="alert" trước khi trải props, nên
    // truyền role={undefined} tường minh sẽ ghi đè mất vai trò mặc định đó.
    <Alert variant={variant} className={className} {...(role ? { role } : {})}>
      {title ? <AlertTitle>{title}</AlertTitle> : null}
      {children ? <AlertDescription className={descriptionClassName}>{children}</AlertDescription> : null}
    </Alert>
  );
}
