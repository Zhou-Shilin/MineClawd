package com.mineclawd.config.ui;

import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.string.IStringController;
import dev.isxander.yacl3.gui.controllers.string.StringControllerElement;
import net.minecraft.text.Text;

import java.util.function.Supplier;

public class MaskedStringControllerElement extends StringControllerElement {
    private Supplier<Boolean> revealSupplier = () -> false;

    public MaskedStringControllerElement(
            IStringController<?> control,
            YACLScreen screen,
            Dimension<Integer> dim,
            boolean instantApply,
            Supplier<Boolean> revealSupplier
    ) {
        super(control, screen, dim, instantApply);
        if (revealSupplier != null) {
            this.revealSupplier = revealSupplier;
        }
    }

    @Override
    protected Text getValueText() {
        boolean reveal = revealSupplier != null && revealSupplier.get();
        if (reveal) {
            return super.getValueText();
        }
        if (!inputFieldFocused && inputField.isEmpty()) {
            return super.getValueText();
        }

        String raw = (instantApply || !inputFieldFocused) ? ((IStringController<?>) control).getString() : inputField;
        return Text.literal(mask(raw));
    }

    private static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return "*".repeat(raw.length());
    }
}
