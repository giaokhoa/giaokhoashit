-- Bản nhận nộp giữ nội dung đã đóng băng của đúng phiên bản nguồn tại thời điểm nộp: cột này mang JSON
-- render-only (văn bản, khối và tham chiếu ảnh render cùng tệp qua liên kết mới của bản nhận), không mang
-- nguồn LaTeX ở bất kỳ khối nào, để bản trung tâm đã nhận tồn tại độc lập — xoá hay sửa bản cá nhân sau đó
-- không đổi được bản nhận. Bảng submission_item đã có Row Level Security theo phân vùng từ V11 nên cột mới
-- không cần policy riêng.
alter table submission_item add column content_snapshot text;
