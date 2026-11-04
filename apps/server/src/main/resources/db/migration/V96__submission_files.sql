-- Tệp bài làm của học viên (lát L2c của gói tệp + LaTeX): một hàng là một liên kết từ một lượt làm tới một
-- blob của nền tệp (lát L1), gắn qua phiên tải lên của chính học viên. Hàng chỉ ghi định danh blob và phiên
-- tải lên, không ghi khoá lưu trữ hay byte: byte và vòng đời của blob thuộc nền tệp. Cột blob_id mang khoá
-- ngoại sang bảng file_blob của nền tệp (tạo ở V92, đã có trên main khi migration này chạy). Trạng thái ghi
-- tại bước chốt gắn; tệp hoàn tất chốt sau hạn của lượt làm được suy trạng thái bị từ chối khi đọc, không
-- sửa hàng đã ghi.
create table submission_file (
    id uuid primary key,
    partition_key varchar(255) not null,
    attempt_id uuid not null,
    upload_session_id uuid not null,
    blob_id uuid references file_blob (id),
    display_name varchar(255) not null,
    content_type varchar(64),
    size_bytes bigint,
    status varchar(32) not null,
    attached_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (id, partition_key),
    unique (attempt_id, upload_session_id),
    constraint fk_submission_file_attempt
        foreign key (attempt_id, partition_key) references exam_attempt (id, partition_key),
    constraint submission_file_status check (status in ('processing', 'ready', 'quarantined')),
    constraint submission_file_content_type check (content_type in ('image/png', 'image/jpeg', 'image/webp')),
    constraint submission_file_size_positive check (size_bytes is null or size_bytes > 0)
);
