package com.mineclawd.llm;

import com.google.gson.JsonObject;

public record OpenAITool(String name, String description, JsonObject parameters) {
}
