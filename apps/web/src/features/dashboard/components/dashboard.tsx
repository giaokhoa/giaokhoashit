import { useMemo } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import '#config/i18n';
import { useAuth } from '#lib/auth';
import { usePermissions, type CourseAccess } from '#lib/permissions';
import { useCoursesGradeData } from '#hooks/use-courses-grade-data';
import { formatWithOptions } from '#utils/date-format';
import { useDashboardOverview } from '../queries/dashboard';
import { displayCourseName, useCourseCardPrefs } from '#lib/course-cards';
import { DashboardSkeleton } from './dashboard-skeleton';
import { SectionCards, type StatCard } from '#components/section-cards';
import { Skeleton } from '@lms/ui/components/skeleton';
import { ChartAreaInteractive, type ChartPoint } from './chart-area-interactive';
import { DataTable, type DataColumn } from '#components/data-table';
import { Badge } from '@lms/ui/components/badge';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';

function SectionHeading({ title }: { title: string }) {
  return (
    <h2 className="px-4 text-lg font-semibold lg:px-6" data-testid="dashboard-section-title">
      {title}
    </h2>
  );
}

/**
 * Khối tổng hợp DASH1 dùng chung cho cả hai vai: đúng MỘT query `GET /dashboard/overview` (TanStack dedupe theo
 * queryKey nên khối GV và khối HS trên cùng trang chỉ phát một request). Endpoint đồng thời cập nhật mốc xem trước phía
 * server khi tính xong danh sách "mới". Hook nay nằm ở tầng queries của feature notifications (F-01 của rà soát #133).
 */

function StudentTodoBlock() {
  const { t, i18n } = useTranslation('dashboard');
  const overview = useDashboardOverview();
  const data = overview.data;
  if (overview.isPending) {
    return (
      <div className="px-4 lg:px-6" data-testid="dashboard-todo">
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (!data) return null;
  const statusLabel = (status: string) =>
    status === 'overdue' ? t('todoOverdue') : status === 'in_progress' ? t('todoInProgress') : t('todoNotStarted');
  return (
    <Card data-testid="dashboard-todo">
      <CardHeader>
        <CardTitle>{t('todoTitle')}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div>
          <h3 className="mb-2 text-sm font-medium">{t('todoDueTitle')}</h3>
          {data.dueItems.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('todoEmpty')}</p>
          ) : (
            <ul className="flex flex-col gap-2">
              {data.dueItems.map((item) => (
                <li
                  key={`${item.kind}-${item.cmId}`}
                  className="flex flex-wrap items-center gap-2 text-sm"
                  data-testid="todo-due-item"
                >
                  <Badge variant={item.status === 'overdue' ? 'destructive' : 'secondary'}>
                    {statusLabel(item.status)}
                  </Badge>
                  {/* Tên việc chỉ hiện dạng chữ, không bọc liên kết: cả hai đích cũ của mục này (trang làm quiz và
                      trang nộp bài tập theo khoá) đều thuộc bề mặt đã ẩn khỏi bản phát hành 07/11 (issue #79) — giữ
                      liên kết sẽ thành đường cụt. */}
                  <span className="font-medium">{item.name}</span>
                  <span className="text-muted-foreground">
                    {item.kind === 'quiz' ? t('todoKindQuiz') : t('todoKindAssignment')} · {item.courseName} ·{' '}
                    {t('todoDueAt', { date: formatWithOptions(item.dueAt, i18n.language, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) })}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
        {data.newResults.length > 0 && (
          <div>
            <h3 className="mb-2 text-sm font-medium">{t('todoNewResultsTitle')}</h3>
            <ul className="flex flex-col gap-2">
              {data.newResults.map((result, idx) => (
                <li key={`${result.kind}-${result.itemId ?? result.courseId}-${idx}`} className="text-sm" data-testid="todo-new-result">
                  <Link
                    to={`/courses/${result.courseId}/grades`}
                    className="font-medium hover:underline"
                  >
                    {`${result.itemName ?? ''} — ${t('newResultGrade', { grade: result.finalGrade ?? '—', max: result.gradeMax ?? '—' })}`}
                  </Link>
                  <span className="text-muted-foreground"> · {result.courseName}</span>
                  {result.feedback && <span className="block text-muted-foreground">{result.feedback}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function TeacherGradingBlock() {
  const { t, i18n } = useTranslation('dashboard');
  const overview = useDashboardOverview();
  const prefs = useCourseCardPrefs();
  const data = overview.data;
  if (overview.isPending) {
    return (
      <div className="px-4 lg:px-6" data-testid="dashboard-grading-queue">
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }
  if (!data) return null;
  return (
    <Card data-testid="dashboard-grading-queue">
      <CardHeader>
        <CardTitle>{t('gradingQueueTitle')}</CardTitle>
      </CardHeader>
      <CardContent>
        {data.gradingQueue.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('gradingQueueEmpty')}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {data.gradingQueue.map((row) => (
              <li key={row.courseId} className="flex flex-wrap items-center gap-2 text-sm" data-testid="grading-queue-row">
                <Link to={`/courses/${row.courseId}/grades`} className="font-medium hover:underline">
                  {displayCourseName(prefs.data?.get(row.courseId), row.courseName)}
                </Link>
                <Badge>{t('gradingPending', { count: row.pendingCount })}</Badge>
                <span className="text-muted-foreground">
                  {row.assignmentPending > 0 ? t('gradingAssignmentPart', { count: row.assignmentPending }) : null}
                  {row.assignmentPending > 0 && row.quizPending > 0 ? ' · ' : null}
                  {row.quizPending > 0 ? t('gradingQuizPart', { count: row.quizPending }) : null}
                  {(row.assignmentPending > 0 || row.quizPending > 0) && row.paperPending > 0 ? ' · ' : null}
                  {/* Số phiếu giấy chỉ hiện dạng chữ: đích xử lý phiếu giấy thuộc bề mặt quiz đã ẩn khỏi bản phát
                      hành 07/11 (issue #79) — giữ liên kết sẽ thành đường cụt. */}
                  {row.paperPending > 0 ? t('gradingPaperPart', { count: row.paperPending }) : null}
                  {row.oldestPendingAt
                    ? ` · ${t('gradingOldest', { date: formatWithOptions(row.oldestPendingAt, i18n.language, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) })}`
                    : ''}
                </span>
                <Button size="sm" variant="outline" render={<Link to={`/courses/${row.courseId}/grades`} />}>
                  {t('viewGradingQueue')}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function TeacherSection() {
  const { t } = useTranslation('dashboard');
  const perms = usePermissions();
  const prefs = useCourseCardPrefs();
  const teaching = perms.teaching;
  const gradeData = useCoursesGradeData(teaching.map((c) => c.course.id));
  const reportOf = (c: CourseAccess) => gradeData.get(c.course.id)?.report ?? null;

  const rows = teaching.flatMap((c) => reportOf(c)?.rows ?? []);
  const graded = rows.filter((r) => Object.values(r.grades).some((g) => g !== null)).length;
  const cards: StatCard[] = [
    {
      id: 'teacher-courses',
      label: t('statTeachingCourses'),
      value: String(teaching.length),
      footer: t('statTeachingFooter'),
    },
    {
      id: 'teacher-students',
      label: t('statMyStudents'),
      value: String(rows.length),
      footer: t('statMyStudentsFooter', { count: graded }),
    },
    {
      id: 'teacher-ungraded',
      label: t('statUngraded'),
      value: String(rows.length - graded),
      footer: t('statUngradedFooter'),
    },
  ];

  const chartData: ChartPoint[] = useMemo(() => {
    const buckets = [
      { label: '0–49%', min: 0, max: 50 },
      { label: '50–64%', min: 50, max: 65 },
      { label: '65–79%', min: 65, max: 80 },
      { label: '80–89%', min: 80, max: 90 },
      { label: '90–100%', min: 90, max: 101 },
    ];
    const counts = buckets.map(() => 0);
    for (const access of teaching) {
      // Đọc thẳng báo cáo từ gradeData thay vì gọi reportOf của component: reportOf là hàm tạo mới ở mỗi lần render nên
      // không thể nằm trong mảng phụ thuộc của useMemo này; phép đọc ở đây tương đương đúng định nghĩa của reportOf.
      const report = gradeData.get(access.course.id)?.report ?? null;
      const maxByItem = new Map((report?.items ?? []).map((i) => [i.id, i.gradeMax]));
      for (const row of report?.rows ?? []) {
        for (const [itemId, grade] of Object.entries(row.grades)) {
          if (grade === null) continue;
          const max = maxByItem.get(itemId);
          if (!max || max <= 0) continue;
          const pct = (grade / max) * 100;
          const idx = buckets.findIndex((b) => pct >= b.min && pct < b.max);
          if (idx >= 0) counts[idx] = (counts[idx] ?? 0) + 1;
        }
      }
    }
    return buckets.map((b, i) => ({ label: b.label, value: counts[i]! }));
  }, [teaching, gradeData]);

  const columns: DataColumn<CourseAccess>[] = [
    {
      id: 'course',
      header: t('colCourse'),
      cell: (c) => <Link to={`/courses/${c.course.id}`}>{displayCourseName(prefs.data?.get(c.course.id), c.course.fullName)}</Link>,
      sortValue: (c) => c.course.fullName,
    },
    {
      id: 'students',
      header: t('colStudents'),
      cell: (c) => String(reportOf(c)?.rows.length ?? 0),
      sortValue: (c) => reportOf(c)?.rows.length ?? 0,
    },
    {
      id: 'ungraded',
      header: t('colUngraded'),
      cell: (c) => String((reportOf(c)?.rows ?? []).filter((r) => !Object.values(r.grades).some((g) => g !== null)).length),
      sortValue: (c) => (reportOf(c)?.rows ?? []).filter((r) => !Object.values(r.grades).some((g) => g !== null)).length,
    },
    {
      id: 'actions',
      header: t('colActions'),
      cell: (c) => (
        <span className="flex gap-2">
          <Button size="sm" variant="outline" render={<Link to={`/courses/${c.course.id}/grades`} />}>
            {t('viewGradebook')}
          </Button>
          <Button size="sm" variant="outline" render={<Link to={`/courses/${c.course.id}/admin`} />}>
            {t('manageCourse')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4 md:gap-6" data-testid="dashboard-teacher">
      <SectionHeading title={t('teacherSection')} />
      <TeacherGradingBlock />
      <SectionCards cards={cards} />
      <ChartAreaInteractive
        title={t('chartTeacherTitle')}
        description={t('chartTeacherDescription')}
        seriesLabel={t('chartTeacherSeries')}
        emptyText={t('chartNoData')}
        data={chartData}
      />
      <DataTable
        title={t('tableTeachingTitle')}
        data={teaching}
        columns={columns}
        getRowId={(c) => c.course.id}
        labels={{
          columns: t('tableColumns'),
          previous: t('tablePrevious'),
          next: t('tableNext'),
          empty: t('emptyTable'),
          filterPlaceholder: t('tableFilterPlaceholder'),
          pageInfo: (page, pages) => t('tablePageInfo', { page, pages }),
        }}
      />
    </section>
  );
}

function StudentSection() {
  const { t } = useTranslation(['dashboard', 'app']);
  const perms = usePermissions();
  const prefs = useCourseCardPrefs();
  const learning = perms.learning;
  const gradeData = useCoursesGradeData(learning.map((c) => c.course.id));
  const gradebookOf = (c: CourseAccess) => gradeData.get(c.course.id)?.gradebook ?? null;

  const activityPcts: number[] = [];
  for (const access of learning) {
    for (const activity of gradebookOf(access)?.activities ?? []) {
      if (activity.grade?.finalGrade !== null && activity.grade?.finalGrade !== undefined && activity.item.gradeMax > 0) {
        activityPcts.push((activity.grade.finalGrade / activity.item.gradeMax) * 100);
      }
    }
  }
  const average =
    activityPcts.length > 0
      ? activityPcts.reduce((sum, pct) => sum + pct, 0) / activityPcts.length
      : null;

  const cards: StatCard[] = [
    {
      id: 'student-courses',
      label: t('statLearningCourses'),
      value: String(learning.length),
      footer: t('statLearningFooter'),
    },
    {
      id: 'student-average',
      label: t('statAverageGrade'),
      value: average === null ? '—' : `${average.toFixed(1)}%`,
      footer: t('statAverageFooter', { count: activityPcts.length }),
    },
  ];

  const columns: DataColumn<CourseAccess>[] = [
    {
      id: 'course',
      header: t('colCourse'),
      cell: (c) => <Link to={`/courses/${c.course.id}`}>{displayCourseName(prefs.data?.get(c.course.id), c.course.fullName)}</Link>,
      sortValue: (c) => c.course.fullName,
    },
    {
      id: 'mygrade',
      header: t('colMyGrade'),
      cell: (c) => {
        const pcts = (gradebookOf(c)?.activities ?? [])
          .filter((a) => a.grade?.finalGrade !== null && a.grade?.finalGrade !== undefined && a.item.gradeMax > 0)
          .map((a) => (a.grade!.finalGrade! / a.item.gradeMax) * 100);
        if (!pcts.length) return '—';
        return `${(pcts.reduce((x, y) => x + y, 0) / pcts.length).toFixed(1)}%`;
      },
      sortValue: (c) => {
        const pcts = (gradebookOf(c)?.activities ?? [])
          .filter((a) => a.grade?.finalGrade !== null && a.grade?.finalGrade !== undefined && a.item.gradeMax > 0)
          .map((a) => (a.grade!.finalGrade! / a.item.gradeMax) * 100);
        return pcts.length ? pcts.reduce((x, y) => x + y, 0) / pcts.length : -1;
      },
    },
    {
      id: 'actions',
      header: t('colActions'),
      cell: (c) => (
        <span className="flex gap-2">
          <Button size="sm" variant="outline" render={<Link to={`/courses/${c.course.id}`} />}>
            {t('openCourse')}
          </Button>
          <Button size="sm" variant="outline" render={<Link to={`/courses/${c.course.id}/grades`} />}>
            {t('viewGradebook')}
          </Button>
        </span>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4 md:gap-6" data-testid="dashboard-student">
      <SectionHeading title={t('studentSection')} />
      <StudentTodoBlock />
      <SectionCards cards={cards} />
      <DataTable
        title={t('tableLearningTitle')}
        data={learning}
        columns={columns}
        getRowId={(c) => c.course.id}
        labels={{
          columns: t('tableColumns'),
          previous: t('tablePrevious'),
          next: t('tableNext'),
          empty: t('emptyTable'),
          filterPlaceholder: t('tableFilterPlaceholder'),
          pageInfo: (page, pages) => t('tablePageInfo', { page, pages }),
        }}
      />
    </section>
  );
}

export function DashboardPage() {
  const { t } = useTranslation(['dashboard', 'app']);
  const { user } = useAuth();
  const perms = usePermissions();

  if (!perms.ready) {
    return <DashboardSkeleton />;
  }

  const hasBlocks = perms.teaching.length > 0 || perms.learning.length > 0;
  const name = user ? `${user.firstName} ${user.lastName}`.trim() || user.username : '';

  return (
    <PageShell width="wide" className="px-0 lg:px-0">
      <h1 className="px-4 text-2xl font-semibold lg:px-6">{t('dashboard:welcome', { name })}</h1>
      {perms.teaching.length > 0 ? <TeacherSection /> : null}
      {perms.learning.length > 0 ? <StudentSection /> : null}
      {!hasBlocks ? (
        <div className="px-4 lg:px-6">
          <Card>
            <CardHeader>
              <CardTitle>{t('dashboard:noBlocksTitle')}</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col items-start gap-4">
              <p className="text-sm text-muted-foreground">{t('dashboard:noBlocksBody')}</p>
              <Button render={<Link to="/courses" />}>{t('dashboard:browseCourses')}</Button>
            </CardContent>
          </Card>
        </div>
      ) : null}
    </PageShell>
  );
}
