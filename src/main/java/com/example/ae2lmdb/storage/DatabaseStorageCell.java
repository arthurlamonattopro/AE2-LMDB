package com.example.ae2lmdb.storage;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.core.definitions.AEItems;
import appeng.util.ConfigInventory;
import appeng.util.prioritylist.IPartitionList;

import com.example.ae2lmdb.config.ModConfig;
import com.example.ae2lmdb.item.DatabaseCellItem;
import com.example.ae2lmdb.item.DatabaseStorageCellItem;

/**
 * {@link StorageCell} para células LMDB do addon (Fase 2 do TODO.md; ampliado nas Fases 3, 4 e 7+).
 *
 * <p><b>Fase 3:</b> o conteúdo agora vive num {@link CellCache} carregado do LMDB (via
 * {@link CellCacheRegistry#getOrLoad}) no momento em que a célula é montada na rede (em
 * {@link DatabaseCellHandler#getCellInventory}), em vez de num mapa vazio recriado a cada
 * montagem. Todo o {@code insert}/{@code extract}/{@code getAvailableStacks} é delegado a um
 * {@link LmdbBackedStorage} construído sobre esse cache.</p>
 *
 * <p><b>Fase 4 — mudanças:</b></p>
 * <ul>
 *   <li>Lê o {@link ItemStack} no construtor (não só o UUID) para acessar o inventário de
 *   config (partition list), o {@link FuzzyMode} e os upgrades — todos persistidos na NBT do
 *   próprio item.</li>
 *
 *   <li>Aplica o <i>partition list</i> em {@link #insert}: chaves que não batem com o
 *   partition (e não casam fuzzy, se um Fuzzy Card estiver instalado) são rejeitadas, igual
 *   ao comportamento nativo do {@code BasicCellInventory} da AE2.</li>
 *
 *   <li>Impõe os caps de capacidade ({@code bytesPerCell}, {@code bytesPerType},
 *   {@code totalTypes}) lidos da {@link ModConfig}. Cada tipo distinto consome
 *   {@code bytesPerType} bytes do total, e o restante fica para as quantidades — mesmo modelo
 *   de custo da AE2 nativa.</li>
 *
 *   <li>Retorna {@link CellState#TYPES_FULL} quando o cap de tipos foi atingido (mas ainda há
 *   bytes livres para inserir mais quantidade em tipos já presentes) e {@link CellState#FULL}
 *   quando ambos os caps foram atingidos. Antes da Fase 4 só havia EMPTY/NOT_EMPTY.</li>
 * </ul>
 *
 * <p><b>Fase 7+ — generalização:</b> agora trabalha com qualquer item que implemente
 * {@link DatabaseCellItem}, incluindo células normais e portáteis, de itens ou fluidos.
 * O filtro de tipo de chave é aplicado no {@code insert} para garantir que células de itens
 * não aceitem fluidos e vice-versa.</p>
 */
public final class DatabaseStorageCell implements StorageCell {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final UUID cellId;
    @Nullable
    private final ISaveProvider container;
    private final LmdbBackedStorage storage;
    private final IPartitionList partitionList;
    private final FuzzyMode fuzzyMode;
    private final DatabaseCellItem cellItem;

    public DatabaseStorageCell(ItemStack cellStack, @Nullable ISaveProvider container) {
        this.cellItem = (DatabaseCellItem) cellStack.getItem();
        UUID id = DatabaseStorageCellItem.getOrCreateCellId(cellStack);
        CellCacheRegistry registry = CellCacheRegistry.getInstance();
        if (!registry.acquireMount(id)) {
            // Fase 5: outra célula já reivindicou este UUID e ainda não foi liberada — duas
            // ItemStacks distintas apontando pro mesmo espaço no LMDB. Gera um UUID novo pra
            // ESTA célula (a original, já montada, não é tocada) e copia o conteúdo atual pra lá,
            // para que as duas passem a ter estoques independentes.
            UUID duplicated = id;
            id = UUID.randomUUID();
            registry.cloneCellData(duplicated, id);
            DatabaseStorageCellItem.setCellId(cellStack, id);
            registry.acquireMount(id);
            LOGGER.warn("AE2 LMDB Cells: UUID duplicado detectado (célula {} já estava montada); "
                    + "célula duplicada recebeu UUID novo {} com cópia do conteúdo", duplicated, id);
        }
        this.cellId = id;
        this.container = container;
        CellCache cache = registry.getOrLoad(cellId);
        this.storage = new LmdbBackedStorage(cache);

        // Constrói o partition list a partir do config inventory do ItemStack. Se o jogador
        // não configurou nada (cell nova), o partitionList fica vazio = aceita tudo (o default
        // de IPartitionList.matchesFilter com lista vazia é true).
        ConfigInventory config = cellItem.getConfigInventory(cellStack);
        IPartitionList.Builder builder = IPartitionList.builder();
        config.keySet().forEach(builder::add);

        // Fuzzy Card upgrade -> o partition list usa fuzzy matching (mesmo modo fuzzy do item).
        // A presença do upgrade é checada via IUpgradeInventory do item (registrados em
        // AE2LmdbMod#commonSetup). ItemDefinition implementa ItemLike, então pode ser passado
        // direto para isInstalled(ItemLike).
        this.fuzzyMode = cellItem.getFuzzyMode(cellStack);
        if (cellItem.getUpgrades(cellStack).isInstalled(AEItems.FUZZY_CARD)) {
            builder.fuzzyMode(fuzzyMode);
        }
        this.partitionList = builder.build();
    }

    /** UUID desta célula — o mesmo guardado na NBT do item (ver {@code DatabaseStorageCellItem}). */
    public UUID getCellId() {
        return cellId;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0) {
            return 0;
        }
        // 0) Filtro de tipo: célula de itens só aceita AEKeyType.items(), fluidos só fluids().
        if (!cellItem.getKeyType().contains(what)) {
            return 0;
        }
        // 1) Partition list: se houver partition configurada (lista não vazia) e a chave não
        //    bater (precisa ou fuzzy), rejeita o insert. matchesFilter com WHITELIST retorna
        //    true para "aceita"; o default de IPartitionList com lista vazia já retorna true
        //    (aceita tudo), igual ao BasicCellInventory.
        if (!partitionList.matchesFilter(what, IncludeExclude.WHITELIST)) {
            return 0;
        }
        // 2) Cap de tipos: se a chave é nova e já estamos no limite, rejeita.
        long currentTypes = storage.size();
        boolean isNewType = !storage.contains(what);
        int maxTypes = ModConfig.common().totalTypes.get();
        if (isNewType && maxTypes > 0 && currentTypes >= maxTypes) {
            return 0;
        }
        // 3) Cap de bytes: calcula quantos bytes ficariam depois do insert, descontando o
        //    overhead por tipo (bytesPerType) — igual ao modelo de custo nativo da AE2.
        int bytesPerType = ModConfig.common().bytesPerType.get();
        long bytesPerCell = ModConfig.common().bytesPerCell.get();
        long usedBytes = storage.bytesUsed(bytesPerType);
        // AE2 usa long para amount, mas "1 unidade" = 1 byte por convenção do modelo de custo.
        long remainingBytes = bytesPerCell - usedBytes;
        if (isNewType) {
            remainingBytes -= bytesPerType;
        }
        if (remainingBytes <= 0) {
            return 0;
        }
        long allowed = Math.min(amount, remainingBytes);
        if (allowed <= 0) {
            return 0;
        }

        long inserted = storage.insert(what, allowed, mode, source);
        if (mode == Actionable.MODULATE && inserted > 0) {
            markChanged();
        }
        return inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!cellItem.getKeyType().contains(what)) {
            return 0;
        }
        long extracted = storage.extract(what, amount, mode, source);
        if (mode == Actionable.MODULATE && extracted > 0) {
            markChanged();
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        storage.getAvailableStacks(out, cellItem.getKeyType()::contains);
    }

    @Override
    public Component getDescription() {
        return storage.getDescription();
    }

    @Override
    public CellState getStatus() {
        if (storage.isEmpty()) {
            return CellState.EMPTY;
        }
        int maxTypes = ModConfig.common().totalTypes.get();
        int bytesPerType = ModConfig.common().bytesPerType.get();
        long bytesPerCell = ModConfig.common().bytesPerCell.get();

        boolean typesFull = maxTypes > 0 && storage.size() >= maxTypes;
        boolean bytesFull = storage.bytesUsed(bytesPerType) >= bytesPerCell;

        if (bytesFull) {
            return CellState.FULL;
        }
        if (typesFull) {
            return CellState.TYPES_FULL;
        }
        return CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return cellItem.getIdleDrain();
    }

    /**
     * Chamado pela AE2 periodicamente, em save do mundo, e ao desmontar a célula da rede (por
     * exemplo, ao remover a célula de uma drive) — os três gatilhos de flush pedidos na Fase 3 do
     * TODO.md. Despacha um flush assíncrono deste cache específico via
     * {@link CellCacheRegistry#flushAsync}, que nunca bloqueia a thread chamadora (roda na thread
     * dedicada de flush do registry). O flush periódico e o de save do mundo (que cobrem todos os
     * caches ativos, não só este) são agendados separadamente por
     * {@code com.example.ae2lmdb.event.WorldSaveHandler}.
     *
     * <p>Também libera a marca de "montada" deste UUID via {@link CellCacheRegistry#releaseMount}
     * (Fase 5) — com a ressalva, documentada em {@link CellCacheRegistry#acquireMount}, de que
     * este método é chamado pela AE2 mesmo quando a célula continua montada (não só ao ser
     * removida da rede).</p>
     */
    @Override
    public void persist() {
        CellCacheRegistry.getInstance().flushAsync(cellId);
        CellCacheRegistry.getInstance().releaseMount(cellId);
        if (container != null) {
            container.saveChanges();
        }
    }

    private void markChanged() {
        if (container != null) {
            container.saveChanges();
        }
    }
}
