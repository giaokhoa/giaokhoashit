import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { rosterPageQueryOptions } from '../queries/messaging';
import { memberPanelContextOf } from '../utils/page-helpers';
import type { MemberPanelBundle } from '../components/member-panel';
import type {
  ContactRelationship,
  ContactRequestView,
  ConversationGroupTreeView,
  ConversationView,
  MemberPanelEntry,
  MessagingCourse,
  PersonSummary,
  RosterMember,
} from '../types/types';

export interface UseMemberPanelDataParams {
  active: ConversationView | null;
  groupTree: ConversationGroupTreeView | null;
  myCourses: MessagingCourse[];
  conversations: ConversationView[];
  myId: string | undefined;
  contacts: string[];
  incomingRequests: ContactRequestView[];
  outgoingRequests: ContactRequestView[];
  blocked: string[];
  contactMessage: string | null;
  nameOf: (userId: string) => string;
  mergePeople: (profiles: Record<string, PersonSummary> | undefined) => void;
  messageContact: (userId: string) => Promise<void>;
  sendContactRequestTo: (userId: string) => Promise<void>;
  respondContactRequest: (userId: string, action: 'accept' | 'decline') => Promise<void>;
  removeGroupMember: (userId: string) => Promise<void> | void;
}

export function useMemberPanelData({
  active,
  groupTree,
  myCourses,
  conversations,
  myId,
  contacts,
  incomingRequests,
  outgoingRequests,
  blocked,
  contactMessage,
  nameOf,
  mergePeople,
  messageContact,
  sendContactRequestTo,
  respondContactRequest,
  removeGroupMember,
}: UseMemberPanelDataParams) {
  const queryClient = useQueryClient();
  const [panelPrime, setPanelPrime] = useState<{
    conversationId: string;
    contextKey: string;
    urls: string[];
    failed: boolean;
    priming: boolean;
  } | null>(null);
  const [panelRefreshTick, setPanelRefreshTick] = useState(0);

  const panelContext = active ? memberPanelContextOf(active, groupTree, myCourses) : null;
  const panelContextKey = !panelContext
    ? ''
    : panelContext.kind === 'free'
      ? 'free'
      : `${panelContext.kind}:${panelContext.id}:${panelContext.kind === 'course' && panelContext.subgroup ? 'sub' : 'whole'}`;

  // Mồi roster cho panel thành viên (lát MSG-T4) mỗi khi đổi conversation nhóm / đổi ngữ cảnh / có mốc làm mới: roster
  // lớp/khoá đọc HẾT các trang con trỏ THẲNG từ server vào cache (panel cần đủ hai nhóm GV/HS cùng lúc, khác bộ chọn
  // người chỉ nạp từng trang theo thao tác; `staleTime: 0` ở lời mồi giữ đúng kỷ luật luôn-đọc-tươi của panel như trước
  // khi chuyển cache); nhóm tự do không có roster — tiến độ mồi xong ngay, entries dựng tại bước suy ra từ danh bạ/hàng
  // chờ trong cache.
  useEffect(() => {
    if (!active || active.type !== 2 || !panelContext) {
      setPanelPrime(null);
      return;
    }
    const conversationId = active.id;
    const context = panelContext;
    const contextKey = panelContextKey;
    let cancelled = false;
    if (context.kind === 'free') {
      setPanelPrime({ conversationId, contextKey, urls: [], failed: false, priming: false });
      return;
    }
    setPanelPrime({ conversationId, contextKey, urls: [], failed: false, priming: true });
    void (async () => {
      const base =
        context.kind === 'class'
          ? `/api/v1/messages/classes/${context.id}/members`
          : `/api/v1/messages/courses/${context.id}/members`;
      const urls: string[] = [];
      let cursor: string | null = null;
      try {
        // Trần 25 trang × 100 dòng: đủ cho lớp/khoá thực tế; vượt
        // trần thì panel hiện phần đã nạp thay vì vòng lặp vô hạn.
        for (let page = 0; page < 25; page += 1) {
          const url: string = cursor ? `${base}?limit=100&cursor=${encodeURIComponent(cursor)}` : `${base}?limit=100`;
          const res = await queryClient.fetchQuery({ ...rosterPageQueryOptions(url), staleTime: 0 });
          urls.push(url);
          cursor = res.nextCursor;
          if (!cursor) break;
        }
        if (!cancelled) setPanelPrime({ conversationId, contextKey, urls, failed: false, priming: false });
      } catch {
        if (!cancelled) setPanelPrime({ conversationId, contextKey, urls, failed: true, priming: false });
      }
    })();
    return () => {
      cancelled = true;
    };
    // Chạy theo id conversation + khoá ngữ cảnh + mốc làm mới; các giá trị còn lại mà thân hiệu ứng đọc (active,
    // panelContext, queryClient) là ảnh chụp tại thời điểm chạy, chỉ dùng để xác định (conversation, ngữ cảnh) và đường
    // roster của lần mồi đó. Giữ disable CÓ CHỦ ĐÍCH cho react-hooks/exhaustive-deps: hiệu ứng mồi roster chạy theo (id
    // conversation, khoá ngữ cảnh, mốc làm mới). Đưa các giá trị ảnh chụp vào mảng phụ thuộc sẽ mồi lại toàn bộ roster
    // (tới 25 trang) và đặt panel về trạng thái đang tải mỗi khi đối tượng conversation đổi — ví dụ mỗi tin nhắn mới
    // trong nhóm — tức đổi hành vi người dùng thấy được, nên không sửa mảng phụ thuộc tại điểm này.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active?.id, panelContextKey, panelRefreshTick]);

  const prime =
    active && panelContext && panelPrime && panelPrime.conversationId === active.id && panelPrime.contextKey === panelContextKey
      ? panelPrime
      : null;
  const panelQueries = useQueries({
    queries: (prime && !prime.priming ? prime.urls : []).map((url) => rosterPageQueryOptions(url)),
  });

  // Hồ sơ các dòng roster đã nạp chảy vào bản đồ tên chung — gác bằng chữ ký tập userId để hiệu ứng không lặp khi mảng
  // kết quả đổi danh tính mỗi lần render mà nội dung không đổi.
  const mergedPanelSigRef = useRef('');
  useEffect(() => {
    const members = panelQueries.flatMap((q) => q.data?.members ?? []);
    if (!members.length) return;
    const sig = members.map((m) => m.userId).join(',');
    if (sig === mergedPanelSigRef.current) return;
    mergedPanelSigRef.current = sig;
    mergePeople(Object.fromEntries(members.map((m) => [m.userId, m])));
  }, [panelQueries, mergePeople]);

  let panelEntries: MemberPanelEntry[] | null = null;
  if (active && panelContext && prime && !prime.priming) {
    if (prime.failed) {
      panelEntries = [];
    } else if (panelContext.kind === 'free') {
      const relationshipOf = (id: string): ContactRelationship => {
        if (contacts.includes(id)) return 'contact';
        if (incomingRequests.some((r) => r.userId === id)) return 'pending_incoming';
        if (outgoingRequests.some((r) => r.userId === id)) return 'pending_outgoing';
        return 'none';
      };
      panelEntries = active.members
        .filter((id) => !blocked.includes(id))
        .map((id) => {
          const profile = active.memberProfiles?.[id];
          return {
            userId: id,
            displayName: profile?.displayName ?? nameOf(id),
            username: profile?.username ?? '',
            role: active.createdBy === id ? ('admin' as const) : ('member' as const),
            relationship: relationshipOf(id),
            canDirectMessage: false,
            isCreator: active.createdBy === id,
          };
        });
    } else if (panelQueries.every((q) => q.data)) {
      const all: RosterMember[] = panelQueries.flatMap((q) => q.data!.members);
      const memberSet = new Set(active.members);
      const rows =
        panelContext.kind === 'course' && panelContext.subgroup ? all.filter((m) => memberSet.has(m.userId)) : all;
      panelEntries = rows.map((m) => ({
        userId: m.userId,
        displayName: m.displayName,
        username: m.username,
        role: m.role,
        relationship: m.relationship,
        canDirectMessage: m.canDirectMessage ?? false,
      }));
    }
  }

  const patchPanelRelationship = useCallback(
    (userId: string, relationship: ContactRelationship) => {
      queryClient.setQueriesData<{ members: RosterMember[]; nextCursor: string | null }>(
        { queryKey: ['messages', 'roster'] },
        (old) =>
          old ? { ...old, members: old.members.map((m) => (m.userId === userId ? { ...m, relationship } : m)) } : old,
      );
    },
    [queryClient],
  );

  const directPartnerIds = new Set<string>();
  for (const c of conversations) {
    if (c.type !== 1) continue;
    for (const memberId of c.members) {
      if (memberId !== myId) directPartnerIds.add(memberId);
    }
  }
  const memberPanel: MemberPanelBundle | null =
    active && panelContext
      ? {
          context: panelContext,
          entries: panelEntries,
          failed: !!prime && prime.failed,
          notice: contactMessage,
          directPartnerIds,
          onMessageMember: (userId) => {
            void messageContact(userId);
          },
          onSendContactRequest: (userId) => {
            void sendContactRequestTo(userId);
          },
          onRespondContactRequest: (userId, action) => {
            void respondContactRequest(userId, action);
          },
          onRemoveMember: (userId) => {
            void removeGroupMember(userId);
          },
        }
      : null;

  return {
    panelRefreshTick,
    setPanelRefreshTick,
    panelContext,
    patchPanelRelationship,
    memberPanel,
  };
}
