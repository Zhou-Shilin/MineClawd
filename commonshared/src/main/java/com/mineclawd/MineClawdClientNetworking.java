package com.mineclawd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.config.MineClawdConfigScreen;
import com.mineclawd.dynamic.DynamicContentRegistry;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionPromptScreen;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class MineClawdClientNetworking {
    private static final int HISTORY_PACKET_MAX_CHARS = 262_144;
    private static boolean initialized = false;

    private MineClawdClientNetworking() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        MineClawdNetworking.register();

        NetworkManager.registerReceiver(NetworkManager.s2c(), MineClawdNetworking.OPEN_CONFIG,
                (buf, context) -> {
                    String broadcastTarget = "self";
                    if (buf.isReadable()) {
                        broadcastTarget = buf.readString(64);
                    }
                    String finalBroadcastTarget = broadcastTarget;
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        MineClawdConfigScreen.syncBroadcastTargetFromServer(finalBroadcastTarget);
                        client.setScreen(MineClawdConfigScreen.create(client.currentScreen, finalBroadcastTarget));
                    });
                });

        NetworkManager.registerReceiver(NetworkManager.s2c(), MineClawdNetworking.SYNC_BROADCAST_TARGET,
                (buf, context) -> {
                    String broadcastTarget = "self";
                    if (buf.isReadable()) {
                        broadcastTarget = buf.readString(64);
                    }
                    String finalBroadcastTarget = broadcastTarget;
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> MineClawdConfigScreen.syncBroadcastTargetFromServer(finalBroadcastTarget));
                });

        NetworkManager.registerReceiver(NetworkManager.s2c(), MineClawdNetworking.SYNC_DYNAMIC_CONTENT,
                (buf, context) -> {
                    String payload = buf.readString(32767);
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        DynamicContentRegistry.applySyncPayload(payload);
                        if (client.world != null) {
                            client.reloadResources();
                        }
                    });
                });

        NetworkManager.registerReceiver(NetworkManager.s2c(), MineClawdNetworking.OPEN_QUESTION,
                (buf, context) -> {
                    String payload = buf.readString(32767);
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        QuestionPromptPayload prompt = QuestionPromptPayload.fromJson(payload);
                        if (prompt == null) {
                            return;
                        }
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("[MineClawd] Please answer the question in the popup window."), false);
                        }
                        client.setScreen(new QuestionPromptScreen(client.currentScreen, prompt));
                    });
                });

        NetworkManager.registerReceiver(NetworkManager.s2c(), MineClawdNetworking.OPEN_HISTORY_BOOK,
                (buf, context) -> {
                    List<Text> pages = readHistoryPages(buf);
                    ItemStack stack = pages == null ? readHistoryBookStack(buf) : ItemStack.EMPTY;
                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (pages != null) {
                            if (pages.isEmpty()) {
                                if (client.player != null) {
                                    client.player.sendMessage(Text.literal("[MineClawd] History is empty."), false);
                                }
                                return;
                            }
                            if (!openHistoryBookScreen(client, pages) && client.player != null) {
                                client.player.sendMessage(Text.literal("[MineClawd] Failed to open history book screen on this Minecraft version."), false);
                            }
                            return;
                        }
                        if (stack == null || stack.isEmpty()) {
                            if (client.player != null) {
                                client.player.sendMessage(Text.literal("[MineClawd] History is empty."), false);
                            }
                            return;
                        }
                        if (!openHistoryBookScreen(client, stack) && client.player != null) {
                            client.player.sendMessage(Text.literal("[MineClawd] Failed to open history book screen on this Minecraft version."), false);
                        }
                    });
                });

        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> sendClientReadyPing());
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> MineClawdConfigScreen.clearBroadcastTargetServerSync());
    }

    private static void sendClientReadyPing() {
        try {
            for (Method method : NetworkManager.class.getMethods()) {
                if (!"sendToServer".equals(method.getName())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 2
                        || method.getParameterTypes()[0] != net.minecraft.util.Identifier.class) {
                    continue;
                }
                Object payload = createClientReadyBuffer(method.getParameterTypes()[1]);
                if (payload == null) {
                    continue;
                }
                method.invoke(null, MineClawdNetworking.CLIENT_READY, payload);
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private static Object createClientReadyBuffer(Class<?> expectedType) {
        if (expectedType == null) {
            return null;
        }
        if (expectedType.isAssignableFrom(PacketByteBuf.class)) {
            return new PacketByteBuf(Unpooled.buffer());
        }
        try {
            Class<?> registryByteBufClass = Class.forName("net.minecraft.network.RegistryByteBuf");
            if (!expectedType.isAssignableFrom(registryByteBufClass)) {
                return null;
            }
            for (Constructor<?> constructor : registryByteBufClass.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 2) {
                    continue;
                }
                Class<?> secondParam = constructor.getParameterTypes()[1];
                Object registryLookup = resolveRegistryLookup(secondParam);
                if (registryLookup == null) {
                    continue;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(Unpooled.buffer(), registryLookup);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static ItemStack readHistoryBookStack(Object buf) {
        if (buf == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = tryReadItemStackDirectly(buf);
        if (stack != null && !stack.isEmpty()) {
            return stack;
        }

        stack = tryDecodeWithAnyItemStackCodec(buf);
        if (stack != null && !stack.isEmpty()) {
            return stack;
        }

        return ItemStack.EMPTY;
    }

    private static List<Text> readHistoryPages(PacketByteBuf buf) {
        if (buf == null) {
            return null;
        }
        int startIndex = buf.readerIndex();
        try {
            String payload = buf.readString(HISTORY_PACKET_MAX_CHARS);
            if (payload == null || payload.isBlank()) {
                return List.of();
            }
            JsonElement rootElement = JsonParser.parseString(payload);
            if (!rootElement.isJsonObject()) {
                buf.readerIndex(startIndex);
                return null;
            }
            JsonObject root = rootElement.getAsJsonObject();
            if (!root.has("pages") || !root.get("pages").isJsonArray()) {
                buf.readerIndex(startIndex);
                return null;
            }
            JsonArray pagesArray = root.getAsJsonArray("pages");
            List<Text> pages = new ArrayList<>();
            for (JsonElement element : pagesArray) {
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                pages.add(deserializeHistoryPage(element));
            }
            return pages;
        } catch (Exception ignored) {
            buf.readerIndex(startIndex);
            return null;
        }
    }

    private static Text deserializeHistoryPage(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Text.empty();
        }

        Text parsed = tryParseRichTextElement(element);
        if (parsed != null) {
            return parsed;
        }

        JsonElement normalized = normalizeJsonStringLayers(element);
        if (normalized != null && normalized != element) {
            parsed = tryParseRichTextElement(normalized);
            if (parsed != null) {
                return parsed;
            }
        }

        String fallback = toRawPageString(normalized == null ? element : normalized);
        return fallback == null || fallback.isBlank() ? Text.empty() : Text.literal(fallback);
    }

    private static Text tryParseRichTextElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonObject() || element.isJsonArray()) {
            Text parsed = tryParseWithTextSerialization(element.toString());
            if (parsed != null) {
                return parsed;
            }
            parsed = tryParseWithLegacySerializer(element.toString());
            if (parsed != null) {
                return parsed;
            }
            return tryParseSimpleStyledText(element);
        }

        String rawPage = toRawPageString(element);
        if (rawPage == null || rawPage.isBlank()) {
            return null;
        }
        return tryParseRichText(rawPage);
    }

    private static String toRawPageString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.isJsonPrimitive() ? element.getAsString() : element.toString();
        } catch (Exception ignored) {
            return element.toString();
        }
    }

    private static JsonElement normalizeJsonStringLayers(JsonElement element) {
        JsonElement cursor = element;
        for (int i = 0; i < 3; i++) {
            if (cursor == null || !cursor.isJsonPrimitive() || !cursor.getAsJsonPrimitive().isString()) {
                break;
            }
            String value;
            try {
                value = cursor.getAsString();
            } catch (Exception ignored) {
                break;
            }
            if (value == null || value.isBlank()) {
                break;
            }
            try {
                JsonElement parsed = JsonParser.parseString(value);
                if (parsed == null || parsed.equals(cursor)) {
                    break;
                }
                cursor = parsed;
            } catch (Exception ignored) {
                break;
            }
        }
        return cursor;
    }

    private static Text tryParseRichText(String rawPage) {
        if (rawPage == null || rawPage.isBlank()) {
            return null;
        }

        Text parsed = tryParseWithTextSerialization(rawPage);
        if (parsed != null) {
            return parsed;
        }

        parsed = tryParseWithLegacySerializer(rawPage);
        if (parsed != null) {
            return parsed;
        }

        JsonElement element;
        try {
            element = JsonParser.parseString(rawPage);
        } catch (Exception ignored) {
            return null;
        }

        if (element == null) {
            return null;
        }

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String unwrapped = toRawPageString(element);
            if (unwrapped == null || unwrapped.equals(rawPage)) {
                return null;
            }
            return tryParseRichText(unwrapped);
        }

        return tryParseRichTextElement(element);
    }

    private static Text tryParseWithTextSerialization(String rawPage) {
        Class<?> serializationClass;
        try {
            serializationClass = Class.forName("net.minecraft.text.Text$Serialization");
        } catch (ClassNotFoundException ignored) {
            return null;
        }

        for (Method method : serializationClass.getMethods()) {
            if (!"fromJson".equals(method.getName())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 2
                    || method.getParameterTypes()[0] != String.class) {
                continue;
            }

            Object registryLookup = resolveRegistryLookup(method.getParameterTypes()[1]);
            if (registryLookup == null) {
                continue;
            }

            try {
                Object value = method.invoke(null, rawPage, registryLookup);
                if (value instanceof Text text) {
                    return text;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Text tryParseWithLegacySerializer(String rawPage) {
        Class<?> serializerClass;
        try {
            serializerClass = Class.forName("net.minecraft.text.Text$Serializer");
        } catch (ClassNotFoundException ignored) {
            return null;
        }

        for (Method method : serializerClass.getMethods()) {
            if (!"fromJson".equals(method.getName())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != String.class) {
                continue;
            }

            try {
                Object value = method.invoke(null, rawPage);
                if (value instanceof Text text) {
                    return text;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Text tryParseSimpleStyledText(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return parseSimpleStyledTextElement(element);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Text parseSimpleStyledTextElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Text.empty();
        }
        if (element.isJsonPrimitive()) {
            return Text.literal(element.getAsString());
        }
        if (element.isJsonArray()) {
            MutableText combined = Text.empty();
            for (JsonElement child : element.getAsJsonArray()) {
                combined.append(parseSimpleStyledTextElement(child));
            }
            return combined;
        }
        if (!element.isJsonObject()) {
            return Text.literal(element.toString());
        }

        JsonObject object = element.getAsJsonObject();
        MutableText text = Text.empty();
        if (object.has("text") && object.get("text").isJsonPrimitive()) {
            text = Text.literal(object.get("text").getAsString());
        }

        applySimpleStyle(text, object);

        if (object.has("extra") && object.get("extra").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("extra")) {
                text.append(parseSimpleStyledTextElement(child));
            }
        }

        return text;
    }

    private static void applySimpleStyle(MutableText text, JsonObject object) {
        if (text == null || object == null) {
            return;
        }
        if (object.has("color") && object.get("color").isJsonPrimitive()) {
            Formatting formatting = Formatting.byName(object.get("color").getAsString());
            if (formatting != null) {
                text.formatted(formatting);
            }
        }
        if (isStyleFlagEnabled(object, "bold")) {
            text.formatted(Formatting.BOLD);
        }
        if (isStyleFlagEnabled(object, "italic")) {
            text.formatted(Formatting.ITALIC);
        }
        if (isStyleFlagEnabled(object, "underlined")) {
            text.formatted(Formatting.UNDERLINE);
        }
        if (isStyleFlagEnabled(object, "strikethrough")) {
            text.formatted(Formatting.STRIKETHROUGH);
        }
        if (isStyleFlagEnabled(object, "obfuscated")) {
            text.formatted(Formatting.OBFUSCATED);
        }
    }

    private static boolean isStyleFlagEnabled(JsonObject object, String key) {
        return object != null
                && key != null
                && object.has(key)
                && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isBoolean()
                && object.get(key).getAsBoolean();
    }

    private static Object resolveRegistryLookup(Class<?> expectedType) {
        if (expectedType == null) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        if (client.world != null) {
            Object worldLookup = client.world.getRegistryManager();
            if (expectedType.isInstance(worldLookup)) {
                return worldLookup;
            }
        }

        if (client.player != null && client.player.getWorld() != null) {
            Object playerLookup = client.player.getWorld().getRegistryManager();
            if (expectedType.isInstance(playerLookup)) {
                return playerLookup;
            }
        }

        Object networkHandler = invokeNoArg(client, "getNetworkHandler");
        if (networkHandler == null) {
            return null;
        }

        Object networkLookup = invokeNoArg(networkHandler, "getRegistryManager");
        if (networkLookup != null && expectedType.isInstance(networkLookup)) {
            return networkLookup;
        }

        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static ItemStack tryReadItemStackDirectly(Object buf) {
        if (buf == null) {
            return null;
        }
        for (Method method : buf.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (!ItemStack.class.isAssignableFrom(returnType)) {
                continue;
            }
            Integer startIndex = captureReaderIndex(buf);
            try {
                Object decoded = method.invoke(buf);
                ItemStack stack = asItemStack(decoded);
                if (stack != null) {
                    return stack;
                }
            } catch (ReflectiveOperationException ignored) {
            } finally {
                restoreReaderIndex(buf, startIndex);
            }
        }
        return null;
    }

    private static ItemStack tryDecodeWithAnyItemStackCodec(Object buf) {
        if (buf == null) {
            return null;
        }

        for (Field field : ItemStack.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object codec;
            try {
                codec = field.get(null);
            } catch (ReflectiveOperationException ignored) {
                continue;
            }
            if (codec == null) {
                continue;
            }

            Method decodeMethod = resolveItemStackDecodeMethod(codec);
            if (decodeMethod == null) {
                continue;
            }

            Object argument = resolveCompatibleArgument(buf, decodeMethod.getParameterTypes()[0]);
            if (argument == null) {
                continue;
            }

            Integer startIndex = captureReaderIndex(buf);
            try {
                Object decoded = decodeMethod.invoke(codec, argument);
                ItemStack stack = asItemStack(decoded);
                if (stack != null) {
                    return stack;
                }
            } catch (ReflectiveOperationException ignored) {
            } finally {
                restoreReaderIndex(buf, startIndex);
            }
        }

        return null;
    }

    private static Method resolveItemStackDecodeMethod(Object codec) {
        if (codec == null) {
            return null;
        }
        for (Method method : codec.getClass().getMethods()) {
            if (!"decode".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (ItemStack.class.isAssignableFrom(returnType)) {
                return method;
            }
            if (java.util.Optional.class.isAssignableFrom(returnType)) {
                return method;
            }
        }
        return null;
    }

    private static Object resolveCompatibleArgument(Object buf, Class<?> argumentType) {
        if (buf == null || argumentType == null) {
            return null;
        }
        if (argumentType.isInstance(buf) || argumentType.isAssignableFrom(buf.getClass())) {
            return buf;
        }
        return null;
    }

    private static Integer captureReaderIndex(Object buf) {
        if (buf == null) {
            return null;
        }
        try {
            Method method = buf.getClass().getMethod("readerIndex");
            Object result = method.invoke(buf);
            if (result instanceof Integer index) {
                return index;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static void restoreReaderIndex(Object buf, Integer index) {
        if (buf == null || index == null || index < 0) {
            return;
        }
        try {
            Method method = buf.getClass().getMethod("readerIndex", int.class);
            method.invoke(buf, index);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static ItemStack asItemStack(Object decoded) {
        if (decoded instanceof ItemStack stack) {
            return stack;
        }
        if (decoded instanceof java.util.Optional<?> optional) {
            Object value = optional.orElse(null);
            if (value instanceof ItemStack stack) {
                return stack;
            }
        }
        return null;
    }

    private static boolean openHistoryBookScreen(MinecraftClient client, ItemStack stack) {
        if (client == null || stack == null || stack.isEmpty()) {
            return false;
        }
        for (Class<?> nested : BookScreen.class.getDeclaredClasses()) {
            try {
                Constructor<?> nestedConstructor = nested.getDeclaredConstructor(ItemStack.class);
                nestedConstructor.setAccessible(true);
                Object contents = nestedConstructor.newInstance(stack);
                Constructor<BookScreen> screenConstructor = BookScreen.class.getDeclaredConstructor(nested);
                screenConstructor.setAccessible(true);
                client.setScreen(screenConstructor.newInstance(contents));
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private static boolean openHistoryBookScreen(MinecraftClient client, List<Text> pages) {
        if (client == null) {
            return false;
        }
        List<Text> safePages = pages == null || pages.isEmpty()
                ? List.of(Text.literal("No history."))
                : new ArrayList<>(pages);
        for (Class<?> nested : BookScreen.class.getDeclaredClasses()) {
            try {
                Object contents = createBookContents(nested, safePages);
                if (contents == null) {
                    continue;
                }
                Constructor<BookScreen> screenConstructor = BookScreen.class.getDeclaredConstructor(nested);
                screenConstructor.setAccessible(true);
                client.setScreen(screenConstructor.newInstance(contents));
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }

    private static Object createBookContents(Class<?> contentsClass, List<Text> pages) {
        if (contentsClass == null) {
            return null;
        }
        try {
            Constructor<?> listConstructor = contentsClass.getDeclaredConstructor(List.class);
            listConstructor.setAccessible(true);
            return listConstructor.newInstance(pages);
        } catch (ReflectiveOperationException ignored) {
        }
        if (!contentsClass.isInterface()) {
            return null;
        }
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            Class<?> returnType = method.getReturnType();
            int paramCount = method.getParameterCount();

            // getPageCount: no-arg, returns int
            if (paramCount == 0 && (returnType == int.class || returnType == Integer.class)) {
                return pages.size();
            }
            // getPage / getPageUnchecked: one int arg, returns Text/StringVisitable
            if (paramCount == 1 && (method.getParameterTypes()[0] == int.class)) {
                int index = 0;
                if (args != null && args.length > 0 && args[0] instanceof Number number) {
                    index = number.intValue();
                }
                if (index < 0 || index >= pages.size()) {
                    return Text.empty();
                }
                return pages.get(index);
            }
            if ("equals".equals(name)) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "MineClawdBookContents[" + pages.size() + " pages]";
            }
            return null;
        };
        return Proxy.newProxyInstance(
                contentsClass.getClassLoader(),
                new Class<?>[]{contentsClass},
                handler
        );
    }
}
