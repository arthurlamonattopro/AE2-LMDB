package com.example.ae2lmdb.item;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKeyType;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.contents.CellConfig;
import appeng.items.tools.powered.AbstractPortableCell;
import appeng.menu.me.common.MEStorageMenu;
import appeng.util.ConfigInventory;

import com.example.ae2lmdb.config.ModConfig;

/**
 * Célula portátil LMDB (Fase 7+ do TODO.md) — combina armazenamento LMDB com bateria interna e
 * GUI/terminal acessível via clique direito, seguindo o padrão {@code PortableCellItem} da AE2.
 *
 * <p>Estende {@link AbstractPortableCell} para herdar funcionalidade de bateria
 * ({@code AEBasePoweredItem}), abertura de menu ({@code IMenuItem}), e upgrades.
 * Implementa {@link DatabaseCellItem} (interface própria do addon) em vez de
 * {@code IBasicCellItem} para evitar que o {@code BasicCellHandler} nativo da AE2 reivindique
 * esta célula e force armazenamento NBT-puro — ver javadoc de {@link DatabaseCellItem} para
 * detalhes.</p>
 *
 * <p>O {@link #keyType} (passado no construtor) define se esta portátil armazena itens
 * ({@code AEKeyType.items()}) ou fluidos ({@code AEKeyType.fluids()}). O menu usado depende do
 * tipo: {@link MEStorageMenu#PORTABLE_ITEM_CELL_TYPE} para itens,
 * {@link MEStorageMenu#PORTABLE_FLUID_CELL_TYPE} para fluidos.</p>
 *
 * <p>Assim como {@link DatabaseStorageCellItem}, a NBT guarda apenas o UUID da célula (chave de
 * indexação no LMDB) mais metadados de upgrade/config/fuzzy/bateria — nunca o conteúdo
 * armazenado. O conteúdo vive no {@link com.example.ae2lmdb.storage.CellCache} carregado via
 * {@link com.example.ae2lmdb.storage.CellCacheRegistry}.</p>
 */
public class DatabasePortableCellItem extends AbstractPortableCell implements DatabaseCellItem {

    /** Tag NBT que guarda o UUID da célula — o único dado de gameplay persistido no ItemStack. */
    private static final String TAG_CELL_ID = "CellId";

    /** Tag NBT que guarda o {@link FuzzyMode} configurado via Cell Workbench. */
    private static final String TAG_FUZZY_MODE = "FuzzyMode";

    private final AEKeyType keyType;

    public DatabasePortableCellItem(AEKeyType keyType, Properties properties) {
        // AbstractPortableCell espera (MenuType, Properties, defaultColor). O MenuType depende do
        // tipo de chave (item vs fluido), e a cor padrão segue o padrão AE2: 14540253 (azul
        // claro) para itens, 16739638 (laranja) para fluidos.
        super(keyType == AEKeyType.items()
                        ? MEStorageMenu.PORTABLE_ITEM_CELL_TYPE
                        : MEStorageMenu.PORTABLE_FLUID_CELL_TYPE,
                properties.stacksTo(1),
                keyType == AEKeyType.items() ? 14540253 : 16739638);
        this.keyType = keyType;
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        // Gera o UUID no momento em que a célula portátil é criada, para que já nasça associada
        // a um espaço próprio no LMDB (mesmo que ainda vazio). Reusa o mesmo helper de
        // DatabaseStorageCellItem para manter consistência.
        DatabaseStorageCellItem.getOrCreateCellId(stack);
    }

    /**
     * Retorna o tipo de chave armazenado por esta célula portátil ({@code AEKeyType.items()} ou
     * {@code AEKeyType.fluids()}).
     */
    public AEKeyType getKeyType() {
        return keyType;
    }

    // ---------------------------------------------------------------------
    // Capacidade/custo — helpers internos lidos pela config, igual a DatabaseStorageCellItem.
    // ---------------------------------------------------------------------

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

    @Override
    public double getChargeRate(ItemStack stack) {
        // Taxa de carregamento da bateria interna. Mesmo valor usado pelas portáteis nativas da AE2.
        return 128d;
    }

    @Override
    public ResourceLocation getRecipeId() {
        // ID da receita usada pelo JEI/REI para mostrar "como craftar" esta célula portátil.
        // Retorna null por enquanto; podemos apontar para uma receita específica depois.
        return null;
    }

    // ---------------------------------------------------------------------
    // ICellWorkbenchItem — fuzzy mode (herdado da interface, mesmo padrão de DatabaseStorageCellItem).
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
        // aqui. Como a nossa célula portátil tem partition list (configTypes), é editável.
        return true;
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack is) {
        // CellConfig.create é o helper da própria AE2 que cria um ConfigInventory de config types
        // já ligado ao ItemStack para persistência automática (lê/escrita na NBT do stack sob a
        // chave "list"). O filter restringe quais chaves podem ser colocadas no partition list
        // (no caso, itens ou fluidos dependendo do keyType da célula).
        // 63 slots é o mesmo tamanho usado pelas cells nativas da AE2 (ver BasicStorageCell).
        return CellConfig.create(keyType.filter(), is, 63);
    }
}
