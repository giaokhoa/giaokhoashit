import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CAP } from '@lms/contracts';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent } from '@lms/ui/components/card';
import { Field, FieldLabel } from '@lms/ui/components/field';
import { PageSkeleton } from '#components/page-skeleton';
import { ErrorAlert } from '#components/error-alert';
import { useTranslation } from 'react-i18next';
import { PageShell } from '#components/page-shell';
import '#config/i18n';
import { useAccessCapabilities, useAccessContexts } from '#lib/access';
import { contextOptionLabel } from '../api/context-labels';
import { AdminSelect } from '../components/admin-select';
import { PermissionsMatrix } from '../components/permissions-matrix';
import { useCapabilityMatrix, useSetCapabilityOverride } from '../queries/admin-roles';
import { groupCapabilitiesByResource } from '../types/admin-roles';

export function AdminPermissionsPage() {
  const { t } = useTranslation('app');
  const [contextOverride, setContextOverride] = useState('');
  /**
   * Sửa đổi lạc quan theo từng ô: vai trò → năng lực → {giá trị bốn trạng thái, ngữ cảnh của lần ghi}. Giá trị này ưu
   * tiên trước ghi đè đã lưu khi hiển thị. Ghi thành công thì mục tương ứng bị xoá để ma trận tải lại không bị nháp cũ
   * che; ghi lỗi thì mục tương ứng bị xoá để ô hoàn lại giá trị đã lưu. Ngữ cảnh đi kèm mỗi mục để lần ghi thất bại
   * muộn của ngữ cảnh khác không xoá nhầm nháp mới — hai lần ghi cùng ô ở hai ngữ cảnh có thể trùng giá trị, chỉ so giá
   * trị thì không phân biệt được.
   */
  const [edits, setEdits] = useState<
    Record<string, Record<string, { permission: number; contextId: string }>>
  >({});
  const [savingCells, setSavingCells] = useState<ReadonlySet<string>>(new Set());
  const [saveError, setSaveError] = useState<string | null>(null);

  const capabilitiesQuery = useAccessCapabilities();
  const contextsQuery = useAccessContexts();
  const contexts = contextsQuery.data ?? [];
  const systemContextId =
    contexts.find((ctx) => ctx.contextLevel === 10)?.id ?? contexts[0]?.id ?? '';
  const contextId = contextOverride || systemContextId;
  /**
   * Gương ngữ cảnh mới nhất để đối chiếu khi lỗi muộn về: callback lỗi của lần ghi chụp ngữ cảnh lúc gọi, còn ngữ cảnh
   * hiển thị có thể đã đổi trong lúc chờ — chỉ hiện lỗi khi hai bên vẫn trùng nhau (xem `handleCellChange`).
   */
  const contextIdRef = useRef(contextId);
  useEffect(() => {
    contextIdRef.current = contextId;
  }, [contextId]);
  const matrixQuery = useCapabilityMatrix(contextId);
  const setOverrideMutation = useSetCapabilityOverride();

  const matrix = matrixQuery.data;
  const groups = useMemo(
    () => groupCapabilitiesByResource(capabilitiesQuery.data ?? []),
    [capabilitiesQuery.data],
  );
  const savedValues = useMemo(() => {
    const map = new Map<string, number>();
    for (const role of matrix?.roles ?? []) {
      for (const override of role.overrides) {
        map.set(`${role.id}:${override.capability}`, override.permission);
      }
    }
    return map;
  }, [matrix]);
  /** Giá trị hiển thị của một ô: sửa đổi lạc quan, rồi ghi đè đã lưu, rồi kế thừa. */
  const valueOf = useCallback(
    (roleId: string, capability: string) =>
      edits[roleId]?.[capability]?.permission ??
      savedValues.get(`${roleId}:${capability}`) ??
      CAP.INHERIT,
    [edits, savedValues],
  );
  const allowCount = useMemo(() => {
    if (!matrix) return 0;
    let count = 0;
    for (const role of matrix.roles) {
      for (const group of groups) {
        for (const capability of group.capabilities) {
          if (valueOf(role.id, capability.name) === CAP.ALLOW) count += 1;
        }
      }
    }
    return count;
  }, [matrix, groups, valueOf]);

  const contextsEmpty = contextsQuery.isSuccess && contexts.length === 0;
  const loadFailed =
    capabilitiesQuery.isError || contextsQuery.isError || matrixQuery.isError || contextsEmpty;
  const loading =
    !loadFailed &&
    (capabilitiesQuery.isPending || contextsQuery.isPending || matrixQuery.isPending);
  const matrixReady = !loadFailed && !loading && matrix !== undefined;

  /**
   * Xoá mục nháp của đúng một lần ghi — chỉ khi mục hiện tại vẫn là lần ghi đó (cùng giá trị và cùng ngữ cảnh): người
   * dùng đổi tiếp ô đó trong lúc chờ, hoặc sửa cùng ô ở ngữ cảnh khác sang cùng giá trị, thì giữ lại sửa mới nhất thay
   * vì xoá nhầm.
   */
  function dropEditFor(roleId: string, capability: string, permission: number, editContextId: string) {
    return (prev: Record<string, Record<string, { permission: number; contextId: string }>>) => {
      const roleEdits = prev[roleId];
      const edit = roleEdits?.[capability];
      if (!edit || edit.permission !== permission || edit.contextId !== editContextId) return prev;
      const next = { ...prev };
      const nextRole = { ...roleEdits };
      delete nextRole[capability];
      if (Object.keys(nextRole).length === 0) delete next[roleId];
      else next[roleId] = nextRole;
      return next;
    };
  }

  const handleCellChange = (roleId: string, capability: string, permission: number) => {
    const key = `${contextId}:${roleId}:${capability}`;
    setEdits((prev) => ({
      ...prev,
      [roleId]: { ...prev[roleId], [capability]: { permission, contextId } },
    }));
    setSavingCells((prev) => new Set(prev).add(key));
    setSaveError(null);
    // Mỗi lần ghi đi qua promise riêng của nó (mutateAsync), không gắn callback theo lần gọi của mutate: callback gắn
    // theo lần gọi chỉ chạy cho lần gọi mới nhất — lần gọi sau tách observer của lần gọi trước — nên lỗi muộn của lần
    // gọi cũ sẽ không bao giờ chạy tới callback lỗi, và guard ngữ cảnh trong dropEditFor cũng không bao giờ được gọi.
    // Promise riêng giữ cho mỗi lần ghi tự dọn khoá/nháp của chính nó dù lần ghi khác đã chen vào.
    void setOverrideMutation
      .mutateAsync({ roleId, capability, contextId, permission })
      .then(
        () => {
          setSavingCells((prev) => {
            const next = new Set(prev);
            next.delete(key);
            return next;
          });
          setEdits(dropEditFor(roleId, capability, permission, contextId));
        },
        () => {
          setSavingCells((prev) => {
            const next = new Set(prev);
            next.delete(key);
            return next;
          });
          setEdits(dropEditFor(roleId, capability, permission, contextId));
          if (contextIdRef.current === contextId) {
            setSaveError(t('permissionsSaveFailed'));
          }
        },
      );
  };

  return (
    <PageShell width="app" contentClassName="gap-3 md:gap-6">
      <div>
        <h1 className="font-heading text-xl font-semibold">{t('navPermissions')}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{t('permissionsDescription')}</p>
      </div>
      {loadFailed ? (
        <>
          <ErrorAlert variant="destructive">{t('permissionsLoadFailed')}</ErrorAlert>
          <div>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                void capabilitiesQuery.refetch();
                void contextsQuery.refetch();
                void matrixQuery.refetch();
              }}
            >
              {t('retry')}
            </Button>
          </div>
        </>
      ) : null}
      {loading ? (
        <PageSkeleton label={t('loading')} rows={['h-9 w-full', 'h-9 w-full', 'h-9 w-full']} />
      ) : null}
      {matrixReady && matrix ? (
        <>
          <div className="max-w-xs">
            <Field>
              <FieldLabel htmlFor="permissions-context">{t('roleContext')}</FieldLabel>
              <AdminSelect
                id="permissions-context"
                value={contextId}
                onValueChange={(next) => {
                  // Đổi ngữ cảnh là đổi tập ghi đè đang xem: xoá sạch sửa đổi lạc quan của ngữ cảnh trước. Tập ô đang
                  // lưu KHÔNG xoá theo — khoá mang ngữ cảnh nên không khoá nhầm ô ở ngữ cảnh mới, và quay lại ngữ cảnh
                  // cũ khi yêu cầu còn chờ thì ô vẫn khoá.
                  setContextOverride(next);
                  setEdits({});
                  setSaveError(null);
                }}
                disabled={contexts.length === 0}
                options={contexts.map((ctx) => ({
                  value: ctx.id,
                  label: contextOptionLabel(contexts, ctx, (key, options) => t(key, options)),
                }))}
              />
            </Field>
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3 md:gap-6">
            <Card>
              <CardContent className="flex flex-col gap-1">
                <span className="text-2xl font-semibold">{matrix.roles.length}</span>
                <span className="text-sm text-muted-foreground">{t('permissionsRoleCount')}</span>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="flex flex-col gap-1">
                <span className="text-2xl font-semibold">{groups.length}</span>
                <span className="text-sm text-muted-foreground">{t('permissionsResourceCount')}</span>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="flex flex-col gap-1">
                <span className="text-2xl font-semibold">{allowCount}</span>
                <span className="text-sm text-muted-foreground">{t('permissionsAllowCount')}</span>
              </CardContent>
            </Card>
          </div>
          {saveError ? <ErrorAlert variant="destructive">{saveError}</ErrorAlert> : null}
          <Card className="gap-0 p-0 shadow-none">
            <CardContent className="p-0">
              <PermissionsMatrix
                contextId={contextId}
                groups={groups}
                matrix={matrix}
                valueOf={valueOf}
                savingCells={savingCells}
                onCellChange={handleCellChange}
              />
            </CardContent>
          </Card>
        </>
      ) : null}
    </PageShell>
  );
}

export default AdminPermissionsPage;

// Biên lỗi + tiêu đề theo route (cụm khung SSR #146, từ rà soát #133).
export { RouteErrorBoundary as ErrorBoundary } from '#components/route-error-boundary';
import { appKeyMeta } from '#lib/route-meta';
export function meta() {
  return appKeyMeta('navPermissions');
}
