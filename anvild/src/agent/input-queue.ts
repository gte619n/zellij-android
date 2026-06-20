import type { SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";

/**
 * A pushable async-iterable of user messages — the streaming-input prompt for `query()`.
 * Streaming-input mode is required for `canUseTool`, `interrupt`, mid-session `setModel`,
 * and durable multi-turn sessions (arch §2, impl plan 1 §4.4).
 */
export class InputQueue implements AsyncIterable<SDKUserMessage> {
  private items: SDKUserMessage[] = [];
  private waiting: ((r: IteratorResult<SDKUserMessage>) => void)[] = [];
  private closed = false;

  push(message: SDKUserMessage): void {
    if (this.closed) return;
    const waiter = this.waiting.shift();
    if (waiter) waiter({ value: message, done: false });
    else this.items.push(message);
  }

  close(): void {
    this.closed = true;
    for (const waiter of this.waiting.splice(0)) waiter({ value: undefined as never, done: true });
  }

  [Symbol.asyncIterator](): AsyncIterator<SDKUserMessage> {
    return {
      next: (): Promise<IteratorResult<SDKUserMessage>> => {
        const item = this.items.shift();
        if (item !== undefined) return Promise.resolve({ value: item, done: false });
        if (this.closed) return Promise.resolve({ value: undefined as never, done: true });
        return new Promise((resolve) => this.waiting.push(resolve));
      },
    };
  }
}

export interface InlineImage {
  mediaType: string;
  data: string; // base64
}

/** Build an SDK user message: text, plus any pasted images as inline image blocks (arch §6.5). */
export function userMessage(text: string, images: InlineImage[] = []): SDKUserMessage {
  const content =
    images.length === 0
      ? text
      : [
          ...(text ? [{ type: "text", text }] : []),
          ...images.map((img) => ({
            type: "image",
            source: { type: "base64", media_type: img.mediaType, data: img.data },
          })),
        ];
  return {
    type: "user",
    message: { role: "user", content },
    parent_tool_use_id: null,
    session_id: "",
  } as unknown as SDKUserMessage;
}
