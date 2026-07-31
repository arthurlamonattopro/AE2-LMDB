package com.example.ae2lmdb.item;

import net.minecraft.world.item.ItemStack;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.util.ConfigInventory;

/**
 * Interface própria do addon para itens que representam uma célula LMDB.
 *
 * <p>Existe para que {@code DatabaseCellHandler}/{@code DatabaseStorageCell} trabalhem com células
 * normais e portáteis de forma uniforme, <b>sem</b> implementar {@code IBasicCellItem}. Não usar
 * {@code IBasicCellItem} é uma decisão deliberada: essa interface faz o {@code BasicCellHandler}
 * nativo da AE2 reivindicar o item e persistir conteúdo em NBT, o oposto da arquitetura deste
 * addon.</p>
 */
public interface DatabaseCellItem extends ICellWorkbenchItem {

    AEKeyType getKeyType();

    int getBytes(ItemStack stack);

    int getBytesPerType(ItemStack stack);

    int getTotalTypes(ItemStack stack);

    double getIdleDrain();

    @Override
    FuzzyMode getFuzzyMode(ItemStack stack);

    @Override
    void setFuzzyMode(ItemStack stack, FuzzyMode mode);

    @Override
    IUpgradeInventory getUpgrades(ItemStack stack);

    @Override
    ConfigInventory getConfigInventory(ItemStack stack);
}
