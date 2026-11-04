-- Khối chấm điểm và công bố: điểm từng mục của một lượt làm, tổng hợp chấm của lượt làm và dấu công bố theo lần
-- giao. Cả ba bảng phân vùng bằng partition_key chứa định danh Organization, đúng khuôn của tenant_record; ràng
-- buộc duy nhất (attempt_id, exam_item_id) là khoá dữ kiện của tính một-lần của chấm tự động và của chấm lại
-- ghi đè đúng một hàng. Khoá ngoại hợp thành sang exam_attempt và exam_assignment của khối giao/làm bài được gắn
-- ở migration PostgreSQL V61, vì hai bảng đó thuộc khoảng migration V50–V59 của khối kia.

create table attempt_item_grade (
    id uuid primary key,
    partition_key varchar(255) not null,
    attempt_id uuid not null,
    exam_item_id uuid not null,
    score numeric(10, 2) not null,
    max_score numeric(10, 2) not null,
    source varchar(16) not null,
    grader_sub varchar(255),
    comment text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (attempt_id, exam_item_id),
    unique (id, partition_key)
);

create table attempt_grade_summary (
    attempt_id uuid primary key,
    partition_key varchar(255) not null,
    overall_comment text,
    graded_by_sub varchar(255),
    graded_at timestamp with time zone not null,
    unique (attempt_id, partition_key)
);

create table assignment_publication (
    assignment_id uuid primary key,
    partition_key varchar(255) not null,
    published_by_sub varchar(255) not null,
    published_at timestamp with time zone not null,
    unique (assignment_id, partition_key)
);
