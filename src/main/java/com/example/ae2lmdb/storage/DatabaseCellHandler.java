package com.example.ae2lmdb.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import com.example.ae2lmdb.item.DatabaseCellItem;

/**
 * Ponte entre a AE2 e as células do addon (Fase 2 do TODO.md; ampliado na Fase 4 e 7+).
 *
 * <p>Registrado via {@code appeng.api.storage.StorageCells.addCellHandler(new DatabaseCellHandler())}
 * em {@code AE2LmdbMod} (durante {@code FMLCommonSetupEvent}, conforme exigido pelo javadoc de
 * {@code StorageCells.addCellHandler}). A partir daí, qualquer {@link ItemStack} cujo item implemente
 * {@link DatabaseCellItem} é reconhecido pela AE2 como célula de armazenamento válida.</p>
 *
 * <p><b>Fase 7+ — generalização:</b> agora reconhece tanto células normais ({@code DatabaseStorageCellItem})
 * quanto portáteis ({@code DatabasePortableCellItem}), desde que implementem {@link DatabaseCellItem}.
 * Isso mantém a decisão arquitetural de NÃO usar {@code IBasicCellItem} — ver javadoc de
 * {@link DatabaseCellItem} para detalhes.</p>
 */
public final class DatabaseCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is.getItem() instanceof DatabaseCellItem;
    }

    @Override
    @Nullable
    public StorageCell getCellInventory(ItemStack is, @Nullable ISaveProvider host) {
        if (!isCell(is)) {
            return null;
        }
        return new DatabaseStorageCell(is, host);
    }
}
