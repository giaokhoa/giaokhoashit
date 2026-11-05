-- Vòng đời của nền tệp: dấu mốc thu gom trên blob và lý do trên dấu vết kiểm toán. Lượt thu gom đầu tiên chỉ
-- ghi mốc khi một blob đã hết tham chiếu hợp lệ và hết thời hạn lưu; byte và bản ghi blob chỉ bị xoá ở lượt
-- sau, khi mốc đã qua khoảng chờ an toàn và việc kiểm lại tham chiếu ngay trong giao dịch xoá vẫn cho kết quả
-- hết tham chiếu (khoá ngoại của liên kết, phiên và khối nội dung là chốt chặn cuối cùng — xem FileService).
alter table file_blob add column gc_marked_at timestamp with time zone;

-- Lần cách ly một blob nguy hiểm phát hiện muộn ghi được lý do vào chính dòng kiểm toán của thao tác đó.
alter table file_audit_log add column detail varchar(500);
