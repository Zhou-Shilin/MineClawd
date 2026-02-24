package com.mineclawd;

public enum AgentStreamEventType {
    START(0),
    DELTA(1),
    DONE(2),
    ERROR(3),
    TOOL_STATUS(4),
    TOOL_STATUS_CLEAR(5);

    private final int id;

    AgentStreamEventType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static AgentStreamEventType fromId(int id) {
        for (AgentStreamEventType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return DELTA;
    }
}
