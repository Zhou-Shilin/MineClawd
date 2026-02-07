package com.mineclawd.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumDropdownControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import com.mineclawd.config.ui.MaskedStringController;
import net.minecraft.client.gui.screen.Screen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public final class MineClawdConfigScreen {
    private MineClawdConfigScreen() {
    }

    public static Screen create(Screen parent) {
        MineClawdConfig.HANDLER.load();
        MineClawdConfig config = MineClawdConfig.get();
        MineClawdConfig defaults = MineClawdConfig.HANDLER.defaults();
        AtomicBoolean revealOpenAiKey = new AtomicBoolean(false);
        AtomicBoolean revealVertexKey = new AtomicBoolean(false);

        Option<MineClawdConfig.LlmProvider> providerOption = Option.<MineClawdConfig.LlmProvider>createBuilder()
                .name(Text.literal("Provider"))
                .description(OptionDescription.of(Text.literal("Select which LLM provider to use.")))
                .binding(defaults.provider, () -> config.provider, value -> config.provider = value)
                .controller(option -> EnumDropdownControllerBuilder.create(option)
                        .formatValue(value -> Text.literal(value.displayName())))
                .build();

        Option<String> openAiEndpointOption = Option.<String>createBuilder()
                .name(Text.literal("Endpoint"))
                .description(OptionDescription.of(Text.literal("OpenAI-compatible base URL (e.g., https://api.openai.com/v1).")))
                .binding(defaults.endpoint, () -> config.endpoint, value -> config.endpoint = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> openAiKeyOption = Option.<String>createBuilder()
                .name(Text.literal("API Key"))
                .description(OptionDescription.of(Text.literal("Your OpenAI API key.")))
                .binding(defaults.apiKey, () -> config.apiKey, value -> config.apiKey = value)
                .customController(option -> new MaskedStringController(option, revealOpenAiKey::get))
                .build();

        Option<Boolean> showOpenAiKeyOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Show API Key"))
                .description(OptionDescription.of(Text.literal("Temporarily reveal the OpenAI API key in this screen.")))
                .binding(false, revealOpenAiKey::get, revealOpenAiKey::set)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<String> openAiModelOption = Option.<String>createBuilder()
                .name(Text.literal("Model"))
                .description(OptionDescription.of(Text.literal("OpenAI model name to use for chat.")))
                .binding(defaults.model, () -> config.model, value -> config.model = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> openAiSummarizeModelOption = Option.<String>createBuilder()
                .name(Text.literal("Summarize Model"))
                .description(OptionDescription.of(Text.literal("OpenAI model used to summarize first-round chat into a session title.")))
                .binding(defaults.summarizeModel, () -> config.summarizeModel, value -> config.summarizeModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexEndpointOption = Option.<String>createBuilder()
                .name(Text.literal("Endpoint"))
                .description(OptionDescription.of(Text.literal("Vertex AI base URL (express mode).")))
                .binding(defaults.vertexEndpoint, () -> config.vertexEndpoint, value -> config.vertexEndpoint = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexKeyOption = Option.<String>createBuilder()
                .name(Text.literal("API Key"))
                .description(OptionDescription.of(Text.literal("Vertex AI API key (express mode).")))
                .binding(defaults.vertexApiKey, () -> config.vertexApiKey, value -> config.vertexApiKey = value)
                .customController(option -> new MaskedStringController(option, revealVertexKey::get))
                .build();

        Option<Boolean> showVertexKeyOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Show API Key"))
                .description(OptionDescription.of(Text.literal("Temporarily reveal the Vertex AI API key in this screen.")))
                .binding(false, revealVertexKey::get, revealVertexKey::set)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<String> vertexModelOption = Option.<String>createBuilder()
                .name(Text.literal("Model"))
                .description(OptionDescription.of(Text.literal("Vertex AI model name or full path (publishers/google/models/...).")))
                .binding(defaults.vertexModel, () -> config.vertexModel, value -> config.vertexModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexSummarizeModelOption = Option.<String>createBuilder()
                .name(Text.literal("Summarize Model"))
                .description(OptionDescription.of(Text.literal("Vertex AI model used to summarize first-round chat into a session title.")))
                .binding(defaults.vertexSummarizeModel, () -> config.vertexSummarizeModel, value -> config.vertexSummarizeModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<Boolean> debugModeOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Debug Mode"))
                .description(OptionDescription.of(Text.literal("Log detailed LLM responses and tool calls to latest.log.")))
                .binding(defaults.debugMode, () -> config.debugMode, value -> config.debugMode = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<Boolean> limitToolCallsOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Limit Tool Calls"))
                .description(OptionDescription.of(Text.literal("Enable a maximum tool call round limit per request.")))
                .binding(defaults.limitToolCalls, () -> config.limitToolCalls, value -> config.limitToolCalls = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<Integer> toolCallLimitOption = Option.<Integer>createBuilder()
                .name(Text.literal("Tool Call Limit"))
                .description(OptionDescription.of(Text.literal("Maximum tool call rounds per request (1-20).")))
                .binding(defaults.toolCallLimit, () -> config.toolCallLimit, value -> config.toolCallLimit = value)
                .controller(option -> IntegerSliderControllerBuilder.create(option).range(1, 20).step(1))
                .build();

        Runnable updateAvailability = () -> {
            boolean openAiSelected = providerOption.pendingValue() == MineClawdConfig.LlmProvider.OPENAI;
            openAiEndpointOption.setAvailable(openAiSelected);
            openAiKeyOption.setAvailable(openAiSelected);
            showOpenAiKeyOption.setAvailable(openAiSelected);
            openAiModelOption.setAvailable(openAiSelected);
            openAiSummarizeModelOption.setAvailable(openAiSelected);

            boolean vertexSelected = providerOption.pendingValue() == MineClawdConfig.LlmProvider.VERTEX_AI;
            vertexEndpointOption.setAvailable(vertexSelected);
            vertexKeyOption.setAvailable(vertexSelected);
            showVertexKeyOption.setAvailable(vertexSelected);
            vertexModelOption.setAvailable(vertexSelected);
            vertexSummarizeModelOption.setAvailable(vertexSelected);

            toolCallLimitOption.setAvailable(limitToolCallsOption.pendingValue());
        };

        providerOption.addEventListener((option, event) -> updateAvailability.run());
        limitToolCallsOption.addEventListener((option, event) -> updateAvailability.run());
        updateAvailability.run();

        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("MineClawd"))
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("LLM"))
                        .option(providerOption)
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("OpenAI"))
                                .option(openAiEndpointOption)
                                .option(openAiKeyOption)
                                .option(showOpenAiKeyOption)
                                .option(openAiModelOption)
                                .option(openAiSummarizeModelOption)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Google Vertex AI"))
                                .option(vertexEndpointOption)
                                .option(vertexKeyOption)
                                .option(showVertexKeyOption)
                                .option(vertexModelOption)
                                .option(vertexSummarizeModelOption)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Agent"))
                                .option(debugModeOption)
                                .option(limitToolCallsOption)
                                .option(toolCallLimitOption)
                                .build())
                        .build())
                .save(MineClawdConfig.HANDLER::save)
                .build()
                .generateScreen(parent);
    }
}
