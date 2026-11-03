import { useCallback, useMemo, useRef, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationDetailQueryOptions, conversationMessagesPageQueryOptions } from '../queries/messaging';
import { queryKeys } from '../queries/keys';
import type { MessagingRealtimeHandle } from '../api/messaging-socket';
import { storeConversationId } from '../utils/page-helpers';
import { mergeMessages } from '../utils/utils';
import type { ConversationDetailView, ConversationView, MessageView, PersonSummary } from '../types/types';

export interface UseActiveConversationParams {
  mergePeople: (profiles: Record<string, PersonSummary> | undefined) => void;
  markRead: (conversationId: string) => Promise<void>;
  myId: string | undefined;
  lastSeqRef: { current: Map<string, number> };
  realtimeRef: { current: MessagingRealtimeHandle | null };
}

export function useActiveConversation({
  mergePeople,
  markRead,
  myId,
  lastSeqRef,
  realtimeRef,
}: UseActiveConversationParams) {
  const queryClient = useQueryClient();
  const [activeId, setActiveId] = useState<string | null>(null);
  /**
   * Bản tóm tắt hội thoại được truyền vào lúc mở — chỉ là mồi hiển
   * thị đầu tiên cho các hội thoại chưa có mặt trong cache danh
   * sách (ensure từ lớp/khoá/nhóm); ngay khi cache chi tiết có dữ
   * kiện, phần chi tiết (trừ cửa sổ tin) phủ lên bản tóm tắt này.
   */
  const [openedSummary, setOpenedSummary] = useState<ConversationView | null>(null);
  const [loadedPages, setLoadedPages] = useState<{ conversationId: string; seqs: number[] } | null>(null);
  /**
   * Tin LẠC QUAN theo từng conversation (bản tạm `localStatus`
   * đang gửi/đã lỗi): dữ kiện chỉ sinh ở client, chưa có bản phía
   * server nên không thuộc cache. Vòng đời của bản tạm kết thúc
   * ngay khi server xác nhận: `mergeServerMessages` dọn bản tạm
   * khỏi state này theo clientMessageId trong cùng lượt gộp bản
   * thật vào cache chi tiết; luật khử trùng của `mergeMessages`
   * ở bước suy ra cửa sổ chỉ còn là lưới an toàn trong lúc xác
   * nhận đang trên đường về.
   */
  const [localMessages, setLocalMessages] = useState<Record<string, MessageView[]>>({});
  const [typingUsers, setTypingUsers] = useState<Record<string, boolean>>({});
  const [onlineUsers, setOnlineUsers] = useState<Record<string, boolean>>({});

  const detailQuery = useQuery({
    ...conversationDetailQueryOptions(activeId ?? ''),
    enabled: !!activeId,
  });
  const detail = detailQuery.data;
  const loadedSeqs = loadedPages && loadedPages.conversationId === activeId ? loadedPages.seqs : [];
  const pageQueries = useQueries({
    queries: loadedSeqs.map((seq) => conversationMessagesPageQueryOptions(activeId ?? '', seq)),
  });

  const active = useMemo<ConversationView | null>(() => {
    if (!activeId) return null;
    const summary = openedSummary && openedSummary.id === activeId ? openedSummary : null;
    if (!detail) return summary;
    const { messages: _detailMessages, ...detailView } = detail;
    return { ...(summary ?? {}), ...detailView } as ConversationView;
  }, [activeId, detail, openedSummary]);
  const activeRef = useRef<ConversationView | null>(null);
  activeRef.current = active;

  let serverWindow: MessageView[] = detail?.messages ?? [];
  for (const pageQuery of pageQueries) {
    if (pageQuery.data) serverWindow = mergeMessages(pageQuery.data.messages, serverWindow);
  }
  const activeLocals = activeId ? (localMessages[activeId] ?? []) : [];
  const messages = activeLocals.length ? mergeMessages(activeLocals, serverWindow) : serverWindow;

  const loadedPageFlags: { seq: number; hasMoreBefore: boolean }[] = [];
  loadedSeqs.forEach((seq, index) => {
    const data = pageQueries[index]?.data;
    if (data) loadedPageFlags.push({ seq, hasMoreBefore: data.hasMoreBefore });
  });
  loadedPageFlags.sort((a, b) => a.seq - b.seq);
  const hasMoreBefore = loadedPageFlags.length ? loadedPageFlags[0]!.hasMoreBefore : (detail?.hasMoreBefore ?? false);

  const readMarkers = useMemo<Record<string, number>>(() => {
    const markers: Record<string, number> = {};
    for (const marker of detail?.memberReadMarkers ?? []) markers[marker.userId] = marker.lastReadSeq;
    return markers;
  }, [detail]);

  function closeConversation() {
    setActiveId(null);
    setOpenedSummary(null);
    storeConversationId(null);
  }

  async function open(conv: ConversationView) {
    setOpenedSummary(conv);
    setActiveId(conv.id);
    // Lưu bền hội thoại đang mở để quay lại màn Tin nhắn là gặp
    // đúng ngữ cảnh gần nhất (lát MSG-T3); deep-link thắng giá trị này.
    storeConversationId(conv.id);
    setTypingUsers({});
    setLoadedPages({ conversationId: conv.id, seqs: [] });
    // fetchQuery mồi cache chi tiết (cửa sổ tin + mốc đọc tự suy ra từ cache) và giữ nguyên ngữ nghĩa kiểm tra tư cách
    // thành viên: lỗi thì lời gọi ném ra cho phía khôi phục/deep-link bỏ qua êm.
    const fresh = await queryClient.fetchQuery(conversationDetailQueryOptions(conv.id));
    mergePeople(fresh.memberProfiles);
    const newest = fresh.messages.length
      ? fresh.messages[fresh.messages.length - 1]!.seq
      : (fresh.lastMessageSeq ?? 0);
    if (newest) lastSeqRef.current.set(conv.id, newest);
    realtimeRef.current?.joinConversation(conv.id);
    await markRead(conv.id);
  }

  const ensurePageLoaded = useCallback(
    async (beforeSeq: number) => {
      const conversationId = activeRef.current?.id;
      if (!conversationId) return null;
      const page = await queryClient.fetchQuery(conversationMessagesPageQueryOptions(conversationId, beforeSeq));
      setLoadedPages((prev) => {
        const seqs = prev && prev.conversationId === conversationId ? prev.seqs : [];
        return seqs.includes(beforeSeq) ? prev : { conversationId, seqs: [...seqs, beforeSeq] };
      });
      return page;
    },
    [queryClient],
  );

  async function loadOlder() {
    if (!activeId) return;
    const oldest = messages.find((m) => !m.localStatus);
    if (!oldest) return;
    await ensurePageLoaded(oldest.seq);
  }

  const mergeServerMessages = useCallback(
    (conversationId: string, incoming: MessageView[]) => {
      queryClient.setQueryData<ConversationDetailView>(queryKeys.messagingConversationDetail(conversationId), (old) =>
        old ? { ...old, messages: mergeMessages(old.messages, incoming) } : old,
      );
      const confirmedClientIds = new Set(
        incoming.map((m) => m.clientMessageId).filter((id): id is string => Boolean(id)),
      );
      if (confirmedClientIds.size === 0) return;
      setLocalMessages((prev) => {
        const list = prev[conversationId];
        if (!list?.some((m) => m.localStatus && m.clientMessageId && confirmedClientIds.has(m.clientMessageId))) {
          return prev;
        }
        return {
          ...prev,
          [conversationId]: list.filter(
            (m) => !(m.localStatus && m.clientMessageId && confirmedClientIds.has(m.clientMessageId)),
          ),
        };
      });
    },
    [queryClient],
  );

  const patchMessages = useCallback(
    (conversationId: string, updater: (messages: MessageView[]) => MessageView[]) => {
      queryClient.setQueryData<ConversationDetailView>(queryKeys.messagingConversationDetail(conversationId), (old) =>
        old ? { ...old, messages: updater(old.messages) } : old,
      );
      queryClient.setQueriesData<{ messages: MessageView[]; hasMoreBefore: boolean }>(
        { queryKey: ['messages', 'conversations', conversationId, 'messages'] },
        (old) => (old ? { ...old, messages: updater(old.messages) } : old),
      );
      setLocalMessages((prev) =>
        prev[conversationId] ? { ...prev, [conversationId]: updater(prev[conversationId]!) } : prev,
      );
    },
    [queryClient],
  );

  const addLocalMessage = useCallback((message: MessageView) => {
    setLocalMessages((prev) => {
      const list = prev[message.conversationId] ?? [];
      const next = list.some((m) => m.id === message.id)
        ? list.map((m) => (m.id === message.id ? message : m))
        : [...list, message];
      return { ...prev, [message.conversationId]: next };
    });
  }, []);

  const setLocalMessageStatus = useCallback(
    (conversationId: string, clientMessageId: string, status: 'sending' | 'failed') => {
      setLocalMessages((prev) => {
        const list = prev[conversationId];
        if (!list) return prev;
        return {
          ...prev,
          [conversationId]: list.map((m) =>
            m.clientMessageId === clientMessageId && m.localStatus ? { ...m, localStatus: status } : m,
          ),
        };
      });
    },
    [],
  );

  /**
   * Áp một mốc đọc lên cache chi tiết (giữ mốc lớn nhất — biên nhận
   * đọc đến muộn không được kéo mốc lùi): đường của `markRead` (mốc
   * của chính mình) và của sự kiện biên nhận đọc realtime.
   */
  const applyReadMarker = useCallback(
    (conversationId: string, userId: string, lastReadSeq: number) => {
      queryClient.setQueryData<ConversationDetailView>(queryKeys.messagingConversationDetail(conversationId), (old) => {
        if (!old) return old;
        const markers = old.memberReadMarkers ?? [];
        const existing = markers.find((m) => m.userId === userId);
        if (existing && existing.lastReadSeq >= lastReadSeq) return old;
        return {
          ...old,
          memberReadMarkers: [...markers.filter((m) => m.userId !== userId), { userId, lastReadSeq }],
        };
      });
    },
    [queryClient],
  );

  const invalidateConversationWindow = useCallback(
    (conversationId: string) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.messagingConversationDetail(conversationId) });
    },
    [queryClient],
  );

  const lastOwnMessage = [...messages].reverse().find((m) => m.userIdFrom === myId && !m.localStatus);
  const seenByAllOthers = Boolean(
    active &&
    lastOwnMessage &&
    active.members
      .filter((memberId) => memberId !== myId)
      .every((memberId) => (readMarkers[memberId] ?? 0) >= lastOwnMessage.seq),
  );
  const typingNames = Object.keys(typingUsers).filter((userId) => typingUsers[userId]);
  // Tầm nhìn xoá mềm ở phía client (server đã chốt theo vai trò):
  // tin đã xoá chỉ còn hiện với người có thẩm quyền kiểm duyệt.
  const visibleMessages = messages.filter((m) => !m.deletedAt || active?.canModerate);

  return {
    active,
    messages,
    hasMoreBefore,
    readMarkers,
    typingUsers,
    setTypingUsers,
    onlineUsers,
    setOnlineUsers,
    activeRef,
    closeConversation,
    open,
    loadOlder,
    ensurePageLoaded,
    mergeServerMessages,
    patchMessages,
    addLocalMessage,
    setLocalMessageStatus,
    applyReadMarker,
    invalidateConversationWindow,
    lastOwnMessage,
    seenByAllOthers,
    typingNames,
    visibleMessages,
  };
}
