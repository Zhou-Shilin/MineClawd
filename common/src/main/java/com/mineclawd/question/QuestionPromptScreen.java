package com.mineclawd.question;

import com.mineclawd.MineClawdNetworking;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

public final class QuestionPromptScreen extends Screen {
    private final Screen parent;
    private final QuestionPromptPayload payload;
    private final long deadlineEpochMillis;
    private TextFieldWidget otherInput;
    private ButtonWidget skipButton;
    private MultilineText questionLines = MultilineText.EMPTY;
    private boolean submitted;

    public QuestionPromptScreen(Screen parent, QuestionPromptPayload payload) {
        super(Text.literal("MineClawd Question"));
        this.parent = parent;
        this.payload = payload;
        long expiresAt = payload == null ? 0L : payload.expiresAtEpochMillis();
        this.deadlineEpochMillis = expiresAt > 0L ? expiresAt : (System.currentTimeMillis() + 60000L);
    }

    @Override
    protected void init() {
        if (payload == null) {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
            return;
        }

        questionLines = MultilineText.create(this.textRenderer, Text.literal(payload.question()), this.width - 40);
        int y = 38 + questionLines.count() * 10;
        int buttonWidth = Math.min(360, this.width - 40);
        int left = (this.width - buttonWidth) / 2;

        List<String> options = payload.options();
        int displayOptionIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (isBuiltInOtherOption(option)) {
                continue;
            }
            final int optionIndex = i;
            int lineY = y + (displayOptionIndex * 24);
            int displayLabelIndex = displayOptionIndex + 1;
            addDrawableChild(ButtonWidget.builder(Text.literal(displayLabelIndex + ". " + option), button ->
                            submitOption(optionIndex, option))
                    .dimensions(left, lineY, buttonWidth, 20)
                    .build());
            displayOptionIndex++;
        }

        int otherY = y + (displayOptionIndex * 24);
        otherInput = new TextFieldWidget(this.textRenderer, left, otherY, buttonWidth - 92, 20, Text.literal("Other"));
        otherInput.setMaxLength(400);
        addDrawableChild(otherInput);
        addDrawableChild(ButtonWidget.builder(Text.literal("Other"), button -> submitOther())
                .dimensions(left + buttonWidth - 88, otherY, 88, 20)
                .build());

        skipButton = addDrawableChild(ButtonWidget.builder(buildSkipLabel(), button -> submitSkip("User skipped."))
                .dimensions(this.width - 122, 10, 112, 20)
                .build());

        setInitialFocus(otherInput);
    }

    @Override
    public void tick() {
        super.tick();
        if (submitted) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= deadlineEpochMillis) {
            submitSkip("Timed out after 60 seconds.");
            return;
        }
        if (skipButton != null) {
            skipButton.setMessage(buildSkipLabel());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // Consume all key events so they don't leak to the underlying screen (e.g. chat)
        return true;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (super.charTyped(chr, modifiers)) {
            return true;
        }
        // Consume all char events so they don't leak to the underlying screen
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (super.mouseScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        // Consume scroll events so they don't leak to the underlying screen (e.g. creative inventory)
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // Consume all mouse clicks so they don't leak through
        return true;
    }

    @Override
    public void close() {
        if (!submitted) {
            if (payload != null) {
                submitSkip("User closed the question dialog.");
            } else if (this.client != null) {
                this.client.setScreen(parent);
            }
            return;
        }
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("MineClawd needs your input"), centerX, 14, 0xFFFFFF);
        questionLines.drawCenterWithShadow(context, centerX, 34, 10, 0xE0E0E0);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Pick an option or provide custom text."), 20, this.height - 26, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    private void submitOption(int optionIndex, String optionText) {
        submit(new QuestionResponsePayload(
                payload.questionId(),
                QuestionResponsePayload.Type.OPTION,
                optionIndex,
                optionText
        ));
    }

    private static boolean isBuiltInOtherOption(String option) {
        if (option == null || option.isBlank()) {
            return false;
        }
        String normalized = option.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace("(", " ")
                .replace(")", " ")
                .replace(".", " ")
                .replace(",", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return "other".equals(normalized)
                || "other option".equals(normalized)
                || "custom".equals(normalized)
                || "custom text".equals(normalized)
                || "\u5176\u4ed6".equals(normalized)
                || "\u5176\u5b83".equals(normalized);
    }

    private void submitOther() {
        String text = otherInput == null ? "" : otherInput.getText();
        if (text == null || text.isBlank()) {
            if (otherInput != null) {
                otherInput.setFocused(true);
            }
            return;
        }
        submit(new QuestionResponsePayload(
                payload.questionId(),
                QuestionResponsePayload.Type.OTHER,
                -1,
                text
        ));
    }

    private void submitSkip(String reason) {
        submit(new QuestionResponsePayload(
                payload.questionId(),
                QuestionResponsePayload.Type.SKIP,
                -1,
                reason == null ? "" : reason
        ));
    }

    private Text buildSkipLabel() {
        long remainingSeconds = Math.max(0L, (deadlineEpochMillis - System.currentTimeMillis() + 999L) / 1000L);
        return Text.literal("Skip (" + remainingSeconds + "s)");
    }

    private void submit(QuestionResponsePayload payload) {
        if (submitted || payload == null || this.client == null) {
            return;
        }
        submitted = true;
        var buffer = new PacketByteBuf(Unpooled.buffer());
        buffer.writeString(payload.toJson());
        NetworkManager.sendToServer(MineClawdNetworking.QUESTION_RESPONSE, buffer);
        this.client.setScreen(parent);
    }
}
