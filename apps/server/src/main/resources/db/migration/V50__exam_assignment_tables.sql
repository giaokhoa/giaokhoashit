-- Khối giao bài và làm bài: lần giao ghim đúng một phiên bản đề đã phát hành cho đúng một lớp, lượt làm là duy
-- nhất cho mỗi (lần giao, học viên) và câu trả lời là duy nhất cho mỗi (lượt làm, mục đề) — hai ràng buộc duy
-- nhất ở cuối mỗi bảng là khoá dữ kiện của tính chống trùng, không dựa vào khoá phía client. Mọi bảng mang
-- khoá phân vùng là Organization ID của trung tâm sở hữu lớp đích theo khuôn của khung tenancy.
create table exam_assignment (
    id uuid primary key,
    partition_key varchar(255) not null,
    class_id uuid not null,
    exam_resource_id uuid not null,
    exam_version_id uuid not null,
    assigned_by_sub varchar(255) not null,
    opens_at timestamp with time zone not null,
    due_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (id, partition_key)
);

create table exam_attempt (
    id uuid primary key,
    partition_key varchar(255) not null,
    assignment_id uuid not null,
    student_sub varchar(255) not null,
    status varchar(32) not null,
    started_at timestamp with time zone,
    submitted_at timestamp with time zone,
    submit_mode varchar(32),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (id, partition_key),
    unique (assignment_id, student_sub),
    constraint fk_exam_attempt_assignment
        foreign key (assignment_id, partition_key) references exam_assignment (id, partition_key)
);

create table attempt_answer (
    id uuid primary key,
    partition_key varchar(255) not null,
    attempt_id uuid not null,
    exam_item_id uuid not null,
    answer text not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (attempt_id, exam_item_id),
    constraint fk_attempt_answer_attempt
        foreign key (attempt_id, partition_key) references exam_attempt (id, partition_key)
);
