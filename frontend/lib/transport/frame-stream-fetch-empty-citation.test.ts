import { describe, expect, it, vi } from 'vitest';

import { frameSourceToResponse } from './frame-stream-fetch';
import { mockFrameSource } from './mock-frame-source';
import type { WsFrame } from './frames';

describe('frameSourceToResponse empty citation result', () => {
  it('does not treat a generic empty streaming frame as a citation result', async () => {
    const onChatCitation = vi.fn();
    const response = await frameSourceToResponse(
      mockFrameSource([
        {
          type: 'chat.answer',
          threadId: 's1',
          msgId: 'msg-1',
          turnId: 'turn-1',
          model: 'm',
          seq: 1,
          delta: '',
          citations: [],
          restrictedResultsOmitted: false,
          status: 'streaming',
        },
        {
          type: 'chat.answer',
          threadId: 's1',
          msgId: 'msg-1',
          turnId: 'turn-1',
          model: 'm',
          seq: 1,
          delta: 'hello',
          citations: [],
          restrictedResultsOmitted: false,
          status: 'done',
        },
      ]),
      { onChatCitation },
    );

    await expect(response.text()).resolves.toBe('hello');
    expect(onChatCitation).not.toHaveBeenCalled();
  });

  it('forwards an initial DIRECT empty citation-only streaming frame', async () => {
    const frames: WsFrame[] = [
      {
        type: 'chat.answer',
        threadId: 's1',
        msgId: 'msg-1',
        turnId: 'turn-1',
        model: 'm',
        seq: 1,
        delta: '',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'streaming',
      },
      {
        type: 'chat.answer',
        threadId: 's1',
        msgId: 'msg-1',
        turnId: 'turn-1',
        model: 'm',
        seq: 1,
        delta: 'hello',
        citations: [],
        restrictedResultsOmitted: false,
        status: 'done',
      },
    ];
    const onChatCitation = vi.fn();

    const response = await frameSourceToResponse(mockFrameSource(frames), {
      allowInitialEmptyCitationResult: true,
      onChatCitation,
    });

    await expect(response.text()).resolves.toBe('hello');
    expect(onChatCitation).toHaveBeenCalledExactlyOnceWith({
      citations: [],
      restrictedResultsOmitted: false,
      turnId: 'turn-1',
    });
  });
});
