package com.example.ae2lmdb.command;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import appeng.api.stacks.AEKey;

import com.example.ae2lmdb.storage.CellCache;
import com.example.ae2lmdb.storage.CellCacheRegistry;

/**
 * Comando administrativo/debug do addon.
 *
 * <p>Subcomandos (todos exigem nível de permissão 2 — operador/cheats):</p>
 * <ul>
 *   <li>{@code /ae2lmdb stats} — resumo de caches ativos e conteúdo.</li>
 *   <li>{@code /ae2lmdb list} — lista células atualmente carregadas.</li>
 *   <li>{@code /ae2lmdb info <uuid>} — detalhes do cache de uma célula carregada.</li>
 *   <li>{@code /ae2lmdb flush} — agenda flush de todos os caches ativos.</li>
 *   <li>{@code /ae2lmdb wipe <uuid> confirm} — apaga o conteúdo de uma célula carregada.</li>
 * </ul>
 *
 * <p>Todos os comandos de leitura operam no cache em memória (decisão arquitetural de não fazer
 * I/O LMDB na thread principal). O flush é apenas enfileirado na thread dedicada do
 * {@link CellCacheRegistry}.</p>
 *
 * <p>Strings visíveis ao jogador são resolvidas via {@code Component.translatable(...)} para
 * permitir localização — ver {@code assets/ae2lmdb/lang/en_us.json} e {@code pt_br.json}.</p>
 */
public final class AE2LmdbCommand {

    private static final int PERMISSION_LEVEL = 2;
    private static final int MAX_INFO_ENTRIES = 20;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ae2lmdb")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .executes(AE2LmdbCommand::showHelp)
                .then(Commands.literal("stats")
                        .executes(AE2LmdbCommand::showStats))
                .then(Commands.literal("list")
                        .executes(AE2LmdbCommand::listCells))
                .then(Commands.literal("info")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(AE2LmdbCommand::showCellInfo)))
                .then(Commands.literal("flush")
                        .executes(AE2LmdbCommand::flushAll))
                .then(Commands.literal("wipe")
                        .then(Commands.argument("uuid", StringArgumentType.word())
                                .executes(AE2LmdbCommand::warnBeforeWipe)
                                .then(Commands.literal("confirm")
                                        .executes(AE2LmdbCommand::wipeCell)))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> title("commands.ae2lmdb.help.title"), false);
        source.sendSuccess(() -> commandHint("/ae2lmdb stats", "commands.ae2lmdb.help.stats"), false);
        source.sendSuccess(() -> commandHint("/ae2lmdb list", "commands.ae2lmdb.help.list"), false);
        source.sendSuccess(() -> commandHint("/ae2lmdb info <uuid>", "commands.ae2lmdb.help.info"), false);
        source.sendSuccess(() -> commandHint("/ae2lmdb flush", "commands.ae2lmdb.help.flush"), false);
        source.sendSuccess(() -> commandHint("/ae2lmdb wipe <uuid> confirm", "commands.ae2lmdb.help.wipe"), false);
        return 1;
    }

    private static int showStats(CommandContext<CommandSourceStack> context) {
        Map<UUID, CellCache> caches = CellCacheRegistry.getInstance().getActiveCaches();
        long totalTypes = caches.values().stream().mapToLong(cache -> cache.contents().size()).sum();
        long totalAmount = caches.values().stream()
                .flatMap(cache -> cache.contents().values().stream())
                .mapToLong(Long::longValue)
                .sum();

        context.getSource().sendSuccess(() -> title("commands.ae2lmdb.stats.title"), false);
        context.getSource().sendSuccess(
                () -> translatableWith("commands.ae2lmdb.stats.loaded",
                        Component.literal(Integer.toString(caches.size())).withStyle(ChatFormatting.AQUA)),
                false);
        context.getSource().sendSuccess(
                () -> translatableWith("commands.ae2lmdb.stats.types",
                        Component.literal(Long.toString(totalTypes)).withStyle(ChatFormatting.AQUA)),
                false);
        context.getSource().sendSuccess(
                () -> translatableWith("commands.ae2lmdb.stats.amount",
                        Component.literal(Long.toString(totalAmount)).withStyle(ChatFormatting.AQUA)),
                false);
        return caches.size();
    }

    private static int listCells(CommandContext<CommandSourceStack> context) {
        Map<UUID, CellCache> caches = CellCacheRegistry.getInstance().getActiveCaches();
        if (caches.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.ae2lmdb.list.empty")
                            .withStyle(ChatFormatting.YELLOW),
                    false);
            return 0;
        }

        context.getSource().sendSuccess(
                () -> titleWithArg("commands.ae2lmdb.list.title", Integer.toString(caches.size())),
                false);
        caches.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> context.getSource().sendSuccess(
                        () -> cellListEntry(entry.getKey(), entry.getValue()), false));
        return caches.size();
    }

    private static int showCellInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID cellId;
        try {
            cellId = parseUuid(context, "uuid");
        } catch (CommandSyntaxException e) {
            return 0;
        }
        CellCache cache = CellCacheRegistry.getInstance().getActiveCaches().get(cellId);
        if (cache == null) {
            context.getSource().sendFailure(Component.translatable("commands.ae2lmdb.cell_not_loaded.insert",
                    cellId.toString(),
                    Component.translatable("commands.ae2lmdb.cell_not_loaded.info")));
            return 0;
        }

        List<Map.Entry<AEKey, Long>> entries = cache.contents().entrySet().stream()
                .sorted(Map.Entry.<AEKey, Long>comparingByValue().reversed())
                .toList();
        long totalAmount = entries.stream().mapToLong(Map.Entry::getValue).sum();

        context.getSource().sendSuccess(
                () -> titleWithArg("commands.ae2lmdb.info.title", cellId.toString()), false);
        context.getSource().sendSuccess(
                () -> Component.translatable("commands.ae2lmdb.info.summary",
                                Component.literal(Integer.toString(entries.size())).withStyle(ChatFormatting.AQUA),
                                Component.literal(Long.toString(totalAmount)).withStyle(ChatFormatting.AQUA)),
                false);

        entries.stream().limit(MAX_INFO_ENTRIES).forEach(entry -> context.getSource().sendSuccess(
                () -> Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY)
                        .append(entry.getKey().getDisplayName())
                        .append(Component.literal(" × " + entry.getValue()).withStyle(ChatFormatting.GRAY)),
                false));
        if (entries.size() > MAX_INFO_ENTRIES) {
            int omitted = entries.size() - MAX_INFO_ENTRIES;
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.ae2lmdb.info.omitted", omitted)
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                    false);
        }
        return entries.size();
    }

    private static int flushAll(CommandContext<CommandSourceStack> context) {
        CellCacheRegistry registry = CellCacheRegistry.getInstance();
        int cacheCount = registry.getActiveCaches().size();
        registry.flushAll();
        context.getSource().sendSuccess(
                () -> Component.translatable("commands.ae2lmdb.flush.scheduled", cacheCount)
                        .withStyle(ChatFormatting.GREEN),
                true);
        return cacheCount;
    }

    private static int warnBeforeWipe(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID cellId;
        try {
            cellId = parseUuid(context, "uuid");
        } catch (CommandSyntaxException e) {
            return 0;
        }
        MutableComponent confirmation = Component.literal("/ae2lmdb wipe " + cellId + " confirm")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/ae2lmdb wipe " + cellId + " confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.ae2lmdb.hover.fill_command"))));
        context.getSource().sendFailure(
                Component.translatable("commands.ae2lmdb.wipe.confirm").append(confirmation));
        return 0;
    }

    private static int wipeCell(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID cellId;
        try {
            cellId = parseUuid(context, "uuid");
        } catch (CommandSyntaxException e) {
            return 0;
        }
        CellCache cache = CellCacheRegistry.getInstance().getActiveCaches().get(cellId);
        if (cache == null) {
            context.getSource().sendFailure(Component.translatable("commands.ae2lmdb.cell_not_loaded.wipe",
                    cellId.toString()));
            return 0;
        }

        int removedTypes = cache.contents().size();
        cache.contents().clear();
        cache.markDirty();
        CellCacheRegistry.getInstance().flushAsync(cellId);
        context.getSource().sendSuccess(
                () -> Component.translatable("commands.ae2lmdb.wipe.success",
                                cellId.toString(), removedTypes)
                        .withStyle(ChatFormatting.RED),
                true);
        return removedTypes;
    }

    private static UUID parseUuid(CommandContext<CommandSourceStack> context, String argumentName)
            throws CommandSyntaxException {
        String value = StringArgumentType.getString(context, argumentName);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.translatable("commands.ae2lmdb.invalid_uuid", value));
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                    .create("Invalid UUID: " + value);
        }
    }

    private static MutableComponent title(String key) {
        return Component.literal("[AE2 LMDB] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.translatable(key).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    }

    private static MutableComponent titleWithArg(String key, String arg) {
        return Component.literal("[AE2 LMDB] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.translatable(key, arg).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    }

    private static MutableComponent translatableWith(String key, net.minecraft.network.chat.Component value) {
        return Component.translatable(key, value);
    }

    private static MutableComponent commandHint(String command, String descriptionKey) {
        return Component.literal(command).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.ae2lmdb.hover.fill_command"))))
                .append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(descriptionKey).withStyle(ChatFormatting.GRAY));
    }

    private static MutableComponent cellListEntry(UUID cellId, CellCache cache) {
        String command = "/ae2lmdb info " + cellId;
        return Component.literal(cellId.toString()).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("commands.ae2lmdb.hover.show_details"))))
                .append(Component.translatable("commands.ae2lmdb.cell.entry",
                                Component.literal(Integer.toString(cache.contents().size())).withStyle(ChatFormatting.AQUA))
                        .withStyle(ChatFormatting.GRAY));
    }
}
