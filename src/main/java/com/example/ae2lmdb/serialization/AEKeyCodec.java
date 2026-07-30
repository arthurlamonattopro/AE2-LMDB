package com.example.ae2lmdb.serialization;

import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Serializa/desserializa {@code AEKey} para bytes, para uso como parte da chave composta
 * gravada no LMDB (ver {@link com.example.ae2lmdb.storage.LmdbManager}).
 *
 * <p>Segue a decisão arquitetural nº5 do AGENTS.md: usa os helpers do próprio AE2
 * ({@code AEKey.toTagGeneric()} / {@code AEKey.fromTagGeneric(CompoundTag)}), sem inventar
 * um formato binário customizado.</p>
 *
 * <p><b>Ponto de atenção ao atualizar a versão do AE2</b> (ver {@code ae2_version} em
 * {@code gradle.properties}): essa é a assinatura usada na 15.4.10 (Forge 1.20.1). Em versões
 * mais novas do AE2 (feitas para MC 1.21+), esses métodos passaram a receber um parâmetro
 * extra de contexto de registro (ex. {@code HolderLookup.Provider}) ou usar as classes
 * {@code ValueInput}/{@code ValueOutput} em vez de {@code CompoundTag} diretamente. Se o
 * compilador reclamar de assinatura ao trocar a versão do AE2, é aqui — e só aqui — que
 * precisa ajustar.</p>
 */
public final class AEKeyCodec {

    private AEKeyCodec() {
    }

    /** Serializa um AEKey (com informação do seu tipo) para bytes, via NBT sem compressão. */
    public static byte[] encode(AEKey key) {
        CompoundTag tag = key.toTagGeneric();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao serializar AEKey para bytes", e);
        }
        return baos.toByteArray();
    }

    /**
     * Desserializa um AEKey a partir de bytes produzidos por {@link #encode(AEKey)}.
     *
     * @throws IllegalStateException se o tipo de chave codificado não estiver mais registrado
     *                                (ex. um addon de terceiros que definia esse AEKeyType foi removido)
     */
    public static AEKey decode(byte[] bytes) {
        CompoundTag tag;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            tag = NbtIo.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao desserializar AEKey a partir de bytes", e);
        }

        AEKey key = AEKey.fromTagGeneric(tag);
        if (key == null) {
            throw new IllegalStateException(
                    "AEKey.fromTagGeneric retornou null para bytes armazenados no LMDB "
                            + "(tipo de chave desconhecido ou não registrado nesta sessão)");
        }
        return key;
    }
}
