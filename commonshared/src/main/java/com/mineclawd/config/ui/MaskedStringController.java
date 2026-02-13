package com.mineclawd.config.ui;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.string.IStringController;
import dev.isxander.yacl3.gui.controllers.string.StringController;
import net.minecraft.text.Text;

import java.util.function.Supplier;

public class MaskedStringController extends StringController implements IStringController<String> {
    private final Supplier<Boolean> revealSupplier;

    public MaskedStringController(Option<String> option, Supplier<Boolean> revealSupplier) {
        super(option);
        this.revealSupplier = revealSupplier == null ? () -> false : revealSupplier;
    }

    public boolean isRevealed() {
        return revealSupplier.get();
    }

    @Override
    public Text formatValue() {
        if (isRevealed()) {
            return Text.literal(getString());
        }
        return Text.literal(mask(getString()));
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
        return new MaskedStringControllerElement(this, screen, widgetDimension, true, revealSupplier);
    }

    private static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return "*".repeat(raw.length());
    }
}
