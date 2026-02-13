package com.mineclawd;

import dev.architectury.injectables.annotations.ExpectPlatform;

public final class MineClawdExpectPlatform {
    private MineClawdExpectPlatform() {
    }

    @ExpectPlatform
    public static MineClawdPlatform createPlatform() {
        throw new AssertionError();
    }
}
