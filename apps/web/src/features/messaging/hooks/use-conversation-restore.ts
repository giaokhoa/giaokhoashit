/**
 * Hook điều phối khôi phục ngữ cảnh của trang Tin nhắn (đợt C của
 * issue #223, lát 1): hai hiệu ứng chạy đúng một lần khi danh sách
 * nạp xong — deep-link từ thông báo (`?conversation=<id>`) thắng
 * ngữ cảnh đã lưu, nếu không có deep-link thì mở lại hội thoại đã
 * lưu bền. Di chuyển từ `pages/messages.tsx` (hiệu ứng dòng
 * 678–742), giữ nguyên kỷ luật: deep-link có sẵn trong danh sách
 * thì mở như cú bấm; chưa có thì lấy chi tiết rồi chèn vào danh
 * sách rồi mở; server là nơi thực thi quyền thành viên nên không
 * phải thành viên/không tồn tại thì bỏ qua êm.
 */
import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { conversationDetailQueryOptions } from '../queries/messaging';
import { readStoredConversationId } from '../utils/page-helpers';
import type { ConversationView } from '../types/types';

export interface UseConversationRestoreParams {
  deepLinkId: string | null;
  listLoaded: boolean;
  conversations: ConversationView[];
  setConversations: (conversations: ConversationView[] | ((prev: ConversationView[]) => ConversationView[])) => void;
  open: (conv: ConversationView) => Promise<void> | void;
  /**
   * Tab khung trái hiện hành (trang đưa xuống): khi tab đang
   * là Nhóm thì KHÔNG khôi phục hội thoại đã lưu (issue #608,
   * điểm A-BS5 — tab Nhóm của bản demo mở vào là trạng thái
   * chưa chọn; hội thoại đã lưu bền thuộc ngữ cảnh tab Trò
   * chuyện). Deep-link không chịu cổng này.
   */
  sidebarTab: string;
}

export function useConversationRestore({
  deepLinkId,
  listLoaded,
  conversations,
  setConversations,
  open,
  sidebarTab,
}: UseConversationRestoreParams) {
  const queryClient = useQueryClient();
  const deepLinkHandledRef = useRef(false);
  const contextRestoredRef = useRef(false);

  useEffect(() => {
    if (!deepLinkId || !listLoaded || deepLinkHandledRef.current) return;
    deepLinkHandledRef.current = true;
    const found = conversations.find((c) => c.id === deepLinkId);
    if (found) {
      void open(found);
      return;
    }
    void (async () => {
      try {
        const detail = await queryClient.fetchQuery(conversationDetailQueryOptions(deepLinkId));
        const summary: ConversationView = {
          id: detail.id,
          type: detail.type,
          name: detail.name,
          members: detail.members,
          unread: detail.unread,
          lastReadSeq: detail.lastReadSeq,
          lastMessageSeq: detail.lastMessageSeq,
          lastMessage: detail.lastMessage,
        };
        setConversations((prev) => (prev.some((c) => c.id === summary.id) ? prev : [summary, ...prev]));
        await open(summary);
      } catch {
      }
    })();
  }, [deepLinkId, listLoaded, conversations, open, queryClient, setConversations]);

  useEffect(() => {
    if (deepLinkId || !listLoaded || contextRestoredRef.current) return;
    if (sidebarTab === 'groups') {
      contextRestoredRef.current = true;
      return;
    }
    contextRestoredRef.current = true;
    const savedId = readStoredConversationId();
    if (!savedId) return;
    const found = conversations.find((c) => c.id === savedId);
    if (found) {
      void open(found);
      return;
    }
    void (async () => {
      try {
        const detail = await queryClient.fetchQuery(conversationDetailQueryOptions(savedId));
        const { messages: _detailMessages, hasMoreBefore: _hasMore, memberReadMarkers: _markers, ...summary } = detail;
        setConversations((prev) => (prev.some((c) => c.id === summary.id) ? prev : [summary, ...prev]));
        await open(summary);
      } catch {
      }
    })();
  }, [deepLinkId, listLoaded, conversations, open, queryClient, setConversations, sidebarTab]);
}
