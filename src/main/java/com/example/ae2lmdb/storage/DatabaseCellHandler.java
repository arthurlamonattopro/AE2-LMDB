package com.example.ae2lmdb.storage;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import com.example.ae2lmdb.item.DatabaseStorageCellItem;

/**
 * Ponte entre a AE2 e as células do addon (Fase 2 do TODO.md; ampliado na Fase 4).
 *
 * <p>Registrado via {@code appeng.api.storage.StorageCells.addCellHandler(new DatabaseCellHandler())}
 * em {@code AE2LmdbMod} (durante {@code FMLCommonSetupEvent}, conforme exigido pelo javadoc de
 * {@code StorageCells.addCellHandler}). A partir daí, qualquer {@link ItemStack} cujo item seja
 * um {@link DatabaseStorageCellItem} é reconhecido pela AE2 como célula de armazenamento válida.</p>
 *
 * <p><b>Fase 4 — mudança:</b> agora repassa o {@link ItemStack} inteiro para o
 * {@link DatabaseStorageCell}, não só o UUID. O cell precisa do stack para ler o inventário de
 * config (partition list), o {@code FuzzyMode} e os upgrades — todos persistidos na NBT do
 * próprio item. O UUID continua sendo a chave de indexação no LMDB, mas esses outros metadados
 * são lidos do stack a cada montagem da célula na rede.</p>
 */
public final class DatabaseCellHandler implements ICellHandler {

    @Override
    public boolean isCell(ItemStack is) {
        return is.getItem() instanceof DatabaseStorageCellItem;
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
