package com.mineclawd.llm;

import java.util.List;

public record OpenAIMessage(String role, String content, List<OpenAIToolCall> toolCalls, String toolCallId) {
    public static OpenAIMessage system(String text) {
        return new OpenAIMessage("system", text, null, null);
    }

    public static OpenAIMessage user(String text) {
        return new OpenAIMessage("user", text, null, null);
    }

    public static OpenAIMessage assistant(String text, List<OpenAIToolCall> toolCalls) {
        return new OpenAIMessage("assistant", text, toolCalls, null);
    }

    public static OpenAIMessage tool(String toolCallId, String text) {
        return new OpenAIMessage("tool", text, null, toolCallId);
    }
}
