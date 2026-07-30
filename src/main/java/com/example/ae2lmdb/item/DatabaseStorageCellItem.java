package com.example.ae2lmdb.item;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.AEBaseItem;
import appeng.items.contents.CellConfig;
import appeng.util.ConfigInventory;

import com.example.ae2lmdb.config.ModConfig;

/**
 * Item da célula de armazenamento (Fase 2 do TODO.md; ampliado na Fase 4).
 *
 * <p>Segue a decisão arquitetural nº1 do AGENTS.md ao pé da letra: a NBT deste item guarda
 * <b>apenas</b> um UUID (tag {@value #TAG_CELL_ID}) — nunca o conteúdo da célula. O conteúdo
 * real (`AEKey -> long`) vive no LMDB, indexado por esse UUID (ver
 * {@link com.example.ae2lmdb.storage.LmdbManager} e, a partir da Fase 3,
 * {@code CellCache}/{@code LmdbBackedStorage}).</p>
 *
 * <p><b>Fase 4 — mudanças:</b></p>
 * <ul>
 *   <li>Agora estende {@link AEBaseItem} em vez de {@link Item} puro — ganha
 *   {@code getRegistryName}, {@code addToMainCreativeTab} e tratamento de tooltip consistente
 *   com o resto do AE2.</li>
 *
 *   <li><b>Implementa {@link ICellWorkbenchItem} (NÃO {@code IBasicCellItem}) —</b> essa troca
 *   corrige um bug real encontrado em produção ("só é possível guardar um tipo de item por
 *   célula"). O motivo: {@code IBasicCellItem} não é só um marcador de tooltip — o próprio
 *   javadoc da AE2 diz "Implement this on any item to register a 'basic cell'". Ou seja,
 *   implementá-la sozinha já registra o item no {@code BasicCellHandler} <em>nativo</em> da
 *   AE2 (que também está registrado via {@code StorageCells.addCellHandler}, junto com o nosso
 *   {@code DatabaseCellHandler}). Como os dois handlers passavam a reivindicar o mesmo item,
 *   o {@code BasicCellHandler} nativo processava a célula usando NBT pura (o oposto da decisão
 *   arquitetural nº1 do AGENTS.md) e interpretava {@code getTotalTypes()==0} (nossa convenção
 *   de "ilimitado") como um cap literal de tipos — travando a célula logo após o primeiro tipo
 *   inserido. {@code ICellWorkbenchItem} é a interface-pai, sem os métodos de
 *   bytes/tipos/idleDrain que fazem a AE2 reconhecer "basic cells" — mantém Cell Workbench
 *   (config inventory + fuzzy mode) funcionando, mas deixa o {@code DatabaseCellHandler} como
 *   único dono do item.</li>
 *
 *   <li>Implementa {@link #getConfigInventory} retornando um {@link ConfigInventory} de
 *   <i>config types</i> — esse é o inventário que a Cell Workbench mostra para o jogador
 *   configurar o particionamento (whitelist de chaves aceitas). A {@code DatabaseStorageCell}
 *   lê esse inventário no momento de montar a célula e constrói um {@code IPartitionList}
 *   para rejeitar inserts que não batem com o partition.</li>
 *
 *   <li>Implementa {@link #getUpgrades} retornando um {@link IUpgradeInventory} persistido na
 *   NBT — esse é o inventário de upgrade cards (Fuzzy Card, Inverter Card, etc.) que a Cell
 *   Workbench mostra e que {@code Upgrades.add} (registrado em {@code AE2LmdbMod#commonSetup})
 *   popula com os cards aceitos.</li>
 * </ul>
 */
public class DatabaseStorageCellItem extends AEBaseItem implements ICellWorkbenchItem {

    /** Tag NBT que guarda o UUID da célula — o único dado de gameplay persistido no ItemStack. */
    private static final String TAG_CELL_ID = "CellId";

    /** Tag NBT que guarda o {@link FuzzyMode} configurado via Cell Workbench. */
    private static final String TAG_FUZZY_MODE = "FuzzyMode";

    public DatabaseStorageCellItem(Properties properties) {
        // Assim como as storage cells nativas da AE2, a célula em si nunca empilha.
        super(properties.stacksTo(1));
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        // Gera o UUID no momento em que a célula é criada, para que já nasça associada a um
        // espaço próprio no LMDB (mesmo que ainda vazio).
        getOrCreateCellId(stack);
    }

    /**
     * Retorna o UUID já associado a esta célula, gerando (e persistindo na NBT) um novo caso
     * ainda não exista.
     *
     * <p>Serve tanto para o fluxo normal de craft ({@link #onCraftedBy}) quanto como
     * salvaguarda para ItemStacks que cheguem sem UUID por outros caminhos (datagen, comando
     * {@code /give} sem NBT, testes). Isso é deliberadamente tolerante aqui; a Fase 5
     * do TODO.md é que trata o problema oposto — dois ItemStacks apontando pro *mesmo* UUID por
     * duplicação (pick block, clonagem por outros mods etc.) — que é um risco diferente deste.</p>
     */
    public static UUID getOrCreateCellId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (tag.hasUUID(TAG_CELL_ID)) {
            return tag.getUUID(TAG_CELL_ID);
        }
        UUID id = UUID.randomUUID();
        tag.putUUID(TAG_CELL_ID, id);
        return id;
    }

    /**
     * Retorna o UUID da célula, ou {@code null} se o ItemStack não tiver um associado ainda
     * (não deveria acontecer para stacks que já passaram por {@link #getOrCreateCellId}).
     */
    public static UUID getCellId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_CELL_ID)) {
            return null;
        }
        return tag.getUUID(TAG_CELL_ID);
    }

    /**
     * Sobrescreve o UUID da célula na NBT do item — usado pela {@code DatabaseStorageCell} na
     * Fase 5 do TODO.md, quando uma duplicação de referência é detectada e um novo UUID (com
     * cópia do conteúdo antigo) precisa ser atribuído ao ItemStack duplicado.
     */
    public static void setCellId(ItemStack stack, UUID id) {
        stack.getOrCreateTag().putUUID(TAG_CELL_ID, id);
    }

    // ---------------------------------------------------------------------
    // Capacidade/custo — NÃO são mais métodos de interface (ver javadoc da classe sobre a
    // troca de IBasicCellItem por ICellWorkbenchItem). Continuam existindo como helpers
    // internos só por documentação/consistência com ModConfig; quem realmente aplica os
    // caps é DatabaseStorageCell, que lê ModConfig diretamente (não passa por este item).
    // ---------------------------------------------------------------------

    public AEKeyType getKeyType() {
        // Célula de itens por enquanto (Fase 4). Fluid cells ficam no backlog (TODO.md, fase 7+).
        return AEKeyType.items();
    }

    public int getBytes(ItemStack is) {
        return ModConfig.common().bytesPerCell.get();
    }

    public int getBytesPerType(ItemStack is) {
        return ModConfig.common().bytesPerType.get();
    }

    public int getTotalTypes(ItemStack is) {
        return ModConfig.common().totalTypes.get();
    }

    public double getIdleDrain() {
        return ModConfig.common().idleDrain.get();
    }

    // ---------------------------------------------------------------------
    // ICellWorkbenchItem — fuzzy mode (já implementado desde a Fase 2).
    // ---------------------------------------------------------------------

    @Override
    public FuzzyMode getFuzzyMode(ItemStack is) {
        CompoundTag tag = is.getTag();
        if (tag == null || !tag.contains(TAG_FUZZY_MODE)) {
            return FuzzyMode.IGNORE_ALL;
        }
        try {
            return FuzzyMode.valueOf(tag.getString(TAG_FUZZY_MODE));
        } catch (IllegalArgumentException e) {
            return FuzzyMode.IGNORE_ALL;
        }
    }

    @Override
    public void setFuzzyMode(ItemStack is, FuzzyMode fzMode) {
        is.getOrCreateTag().putString(TAG_FUZZY_MODE, fzMode.name());
    }

    // ---------------------------------------------------------------------
    // IUpgradeableItem — inventário de upgrades (Fuzzy Card, Inverter Card, etc.)
    // ---------------------------------------------------------------------

    @Override
    public IUpgradeInventory getUpgrades(ItemStack is) {
        // UpgradeInventories.forItem lê/escreve automaticamente na NBT do stack sob a chave
        // padrão; o wrapper retornado cuida da validação (quais upgrades são aceitos, máximo
        // por slot, etc.) com base no que registrarmos via Upgrades.add(...) em commonSetup.
        // 4 slots é o mesmo número de slots de upgrade que as cells nativas da AE2 oferecem
        // na Cell Workbench (Fuzzy, Inverter, Equal Distribution, Void — um de cada).
        return UpgradeInventories.forItem(is, 4);
    }

    // ---------------------------------------------------------------------
    // ICellWorkbenchItem — config inventory (partition list) e editabilidade.
    // ---------------------------------------------------------------------

    @Override
    public boolean isEditable(ItemStack is) {
        // A Cell Workbench só permite editar o config inventory de células que retornam true
        // aqui. Como a nossa célula tem partition list (configTypes), é editável.
        return true;
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack is) {
        // CellConfig.create é o helper da própria AE2 que cria um ConfigInventory de config types
        // já ligado ao ItemStack para persistência automática (lê/escrita na NBT do stack sob a
        // chave "list"). O filter restringe quais chaves podem ser colocadas no partition list
        // (no caso, só itens — fluidos ficam para o backlog de Fase 7+).
        // 63 slots é o mesmo tamanho usado pelas cells nativas da AE2 (ver BasicStorageCell).
        return CellConfig.create(getKeyType().filter(), is, 63);
    }
}
