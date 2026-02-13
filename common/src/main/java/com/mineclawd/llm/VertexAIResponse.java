package com.mineclawd.llm;

import java.util.List;

public record VertexAIResponse(String text, List<VertexAIToolCall> toolCalls, VertexAIMessage modelMessage) {
}
