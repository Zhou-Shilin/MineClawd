package com.mineclawd.llm;

import com.google.gson.JsonObject;

public record VertexAIToolCall(String name, JsonObject args) {
}
