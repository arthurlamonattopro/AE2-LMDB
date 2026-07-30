package com.example.ae2lmdb.storage;

import org.lmdbjava.ByteArrayProxy;
import org.lmdbjava.Cursor;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.GetOp;
import org.lmdbjava.Txn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.example.ae2lmdb.config.ModConfig;

/**
 * Gerencia o ciclo de vida do ambiente LMDB de um save de mundo (Fase 1 do TODO.md).
 *
 * <p><b>Decisão de arquitetura — sub-databases</b> (ver AGENTS.md, item 6): uma única DB nomeada
 * {@value #DB_NAME}, com chave composta = 16 bytes do UUID da célula + bytes do {@code AEKey}
 * serializado ({@link com.example.ae2lmdb.serialization.AEKeyCodec}). Isso evita ter que declarar
 * de antemão quantas sub-databases o LMDB vai precisar.</p>
 *
 * <p><b>Decisão de arquitetura — proxy de buffer</b> (ver AGENTS.md, item 7): usa
 * {@code byte[]} + {@link ByteArrayProxy#PROXY_BA} em vez de {@code ByteBuffer} +
 * {@code ByteBufferProxy}. O lmdbjava, ao ser usado com {@code ByteBufferProxy} (mesmo pedindo o
 * variante "segura" {@code PROXY_SAFE}), acaba carregando uma classe interna que tenta acessar
 * reflexivamente o campo {@code java.nio.Buffer.address} — e a partir do JDK 16+ isso lança
 * {@code InaccessibleObjectException} a menos que a JVM seja iniciada com
 * {@code --add-opens java.base/java.nio=ALL-UNNAMED}. Como este addon roda dentro da JVM de
 * terceiros (o jogador/administrador do servidor, que não vamos pedir pra configurar flags
 * especiais), a opção com {@code byte[]} evita esse problema por completo, ao custo de uma cópia
 * extra de array em vez de acesso direto à memória mapeada — aceitável nesta fase; revisar se
 * profiling na Fase 6 (performance) apontar isso como gargalo real.</p>
 *
 * <p>Esta classe é <b>deliberadamente</b> livre de qualquer dependência do Minecraft/Forge/AE2 —
 * só usa {@code java.nio}/{@code java.io} e o lmdbjava — justamente para poder ser testada
 * isoladamente (ver {@code LmdbManagerTest} em {@code src/test/java}), sem precisar subir um
 * servidor ou inicializar registries do jogo.</p>
 *
 * <p>Em produção, o {@link Path} passado para {@link #open(Path)} deve vir de
 * {@code level.getServer().getWorldPath(new LevelResource("ae2lmdb"))} (decisão arquitetural nº3
 * em AGENTS.md) — nunca um diretório fixo/global. A abertura/fechamento em resposta aos eventos
 * de load/unload do mundo fica a cargo de {@code WorldSaveHandler} (Fase 3).</p>
 *
 * <p><b>Fase 4:</b> o tamanho máximo do mapa LMDB ({@code mapSize}) agora é lido da
 * {@link ModConfig} em vez de hardcoded em {@link #DEFAULT_MAP_SIZE}. O fallback
 * {@link #DEFAULT_MAP_SIZE} continua existindo só para os testes unitários (que não inicializam
 * o ForgeConfigSpec) poderem abrir um {@code LmdbManager} sem passar pela config.</p>
 */
public final class LmdbManager implements AutoCloseable {

    /** Nome da (única) DB nomeada dentro do ambiente LMDB. */
    static final String DB_NAME = "cells";

    /**
     * Tamanho máximo (virtual) do mapa de memória <b>fallback</b>, usado quando a config não
     * está disponível (ex.: testes unitários que não inicializam o ForgeConfigSpec). Em runtime
     * real, {@link #open(Path)} lê {@link ModConfig.Common#lmdbMapSizeBytes}.
     *
     * <p>LMDB reserva esse espaço de endereçamento antecipadamente, mas o arquivo em disco só
     * cresce sob demanda (é esparso) — não aloca 1 GiB de disco de cara.</p>
     */
    static final long DEFAULT_MAP_SIZE = 1L << 30; // 1 GiB

    private final Env<byte[]> env;
    private final Dbi<byte[]> db;

    private LmdbManager(Env<byte[]> env, Dbi<byte[]> db) {
        this.env = env;
        this.db = db;
    }

    /**
     * Abre (ou cria) o ambiente LMDB dentro de {@code baseDir}, usando o tamanho de mapa
     * configurado em {@link ModConfig.Common#lmdbMapSizeBytes}. Se a config ainda não estiver
     * disponível (testes isolados), cai no fallback {@link #DEFAULT_MAP_SIZE}.
     *
     * @param baseDir diretório onde o arquivo de dados do LMDB vai viver; é criado se não existir.
     */
    public static LmdbManager open(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar o diretório do LMDB: " + baseDir, e);
        }

        long mapSize = resolveMapSize();
        Env<byte[]> env = Env.create(ByteArrayProxy.PROXY_BA)
                .setMapSize(mapSize)
                .setMaxDbs(1)
                .open(baseDir.toFile());

        Dbi<byte[]> db = env.openDbi(DB_NAME, DbiFlags.MDB_CREATE);
        return new LmdbManager(env, db);
    }

    /**
     * Resolve o tamanho do mapa LMDB: lê da {@link ModConfig} se disponível, senão usa
     * {@link #DEFAULT_MAP_SIZE}. O try/catch é necessário porque a config é carregada pelo Forge
     * depois do classloading, e os testes unitários não passam por esse fluxo.
     */
    private static long resolveMapSize() {
        try {
            return ModConfig.common().lmdbMapSizeBytes.get();
        } catch (Throwable t) {
            // Config ainda não carregada (ex.: teste unitário) — usa fallback.
            return DEFAULT_MAP_SIZE;
        }
    }

    /**
     * Grava (ou substitui) o valor associado a um {@code AEKey} já serializado, dentro de uma célula.
     *
     * @param cellId   UUID da célula (nunca guardado na NBT do item além do próprio UUID, ver AGENTS.md item 1)
     * @param keyBytes bytes do AEKey, tipicamente vindos de {@code AEKeyCodec.encode(AEKey)}
     * @param amount   quantidade armazenada para esse AEKey
     */
    public void put(UUID cellId, byte[] keyBytes, long amount) {
        byte[] key = encodeKey(cellId, keyBytes);
        byte[] value = ByteBuffer.allocate(Long.BYTES).putLong(amount).array();

        try (Txn<byte[]> txn = env.txnWrite()) {
            db.put(txn, key, value);
            txn.commit();
        }
    }

    /**
     * Lê o valor associado a um {@code AEKey} já serializado, dentro de uma célula.
     *
     * @return a quantidade armazenada, ou {@code null} se esse AEKey não existe na célula.
     */
    public Long get(UUID cellId, byte[] keyBytes) {
        byte[] key = encodeKey(cellId, keyBytes);
        try (Txn<byte[]> txn = env.txnRead()) {
            byte[] found = db.get(txn, key);
            return found == null ? null : ByteBuffer.wrap(found).getLong();
        }
    }

    /** Remove o par AEKey -&gt; valor de dentro de uma célula específica. */
    public void remove(UUID cellId, byte[] keyBytes) {
        byte[] key = encodeKey(cellId, keyBytes);
        try (Txn<byte[]> txn = env.txnWrite()) {
            db.delete(txn, key);
            txn.commit();
        }
    }

    /**
     * Um par (bytes do AEKey serializado, quantidade) — a mesma representação usada por
     * {@link #loadAll(UUID)} e {@link #replaceAll(UUID, Collection)}. Os bytes aqui já vêm
     * <b>sem</b> o prefixo de UUID da célula (esse prefixo é um detalhe interno da chave composta,
     * ver {@link #encodeKey}).
     */
    public record Entry(byte[] keyBytes, long amount) {
    }

    /**
     * Carrega todos os pares AEKey-&gt;quantidade de uma célula (Fase 3 do TODO.md — usado por
     * {@code CellCache} para reconstruir o cache em memória ao montar a célula na rede).
     *
     * <p>Usa um cursor posicionado no início do prefixo de 16 bytes do UUID (via
     * {@code MDB_SET_RANGE}) e avança enquanto a chave continuar começando com esse prefixo —
     * viável porque o LMDB ordena chaves lexicograficamente por padrão e o UUID é o prefixo fixo
     * da chave composta (ver AGENTS.md, item 6).</p>
     */
    public List<Entry> loadAll(UUID cellId) {
        byte[] prefix = uuidPrefix(cellId);
        List<Entry> result = new ArrayList<>();

        try (Txn<byte[]> txn = env.txnRead();
                Cursor<byte[]> cursor = db.openCursor(txn)) {
            boolean found = cursor.get(prefix, GetOp.MDB_SET_RANGE);
            while (found && startsWith(cursor.key(), prefix)) {
                byte[] key = cursor.key();
                byte[] suffix = Arrays.copyOfRange(key, prefix.length, key.length);
                long amount = ByteBuffer.wrap(cursor.val()).getLong();
                result.add(new Entry(suffix, amount));
                found = cursor.next();
            }
        }
        return result;
    }

    /**
     * Substitui, numa única transação, todas as entradas de uma célula pelo conteúdo de
     * {@code entries} — usado pelo flush do {@code CellCache} (Fase 3). Primeiro apaga todo o
     * intervalo de chaves com o prefixo de UUID da célula, depois grava as entradas atuais; isso
     * evita ter que calcular um diff (o que entrou/saiu) entre o cache em memória e o que já está
     * no disco.
     */
    public void replaceAll(UUID cellId, Collection<Entry> entries) {
        byte[] prefix = uuidPrefix(cellId);

        try (Txn<byte[]> txn = env.txnWrite()) {
            try (Cursor<byte[]> cursor = db.openCursor(txn)) {
                boolean found = cursor.get(prefix, GetOp.MDB_SET_RANGE);
                while (found && startsWith(cursor.key(), prefix)) {
                    cursor.delete();
                    found = cursor.next();
                }
            }

            for (Entry entry : entries) {
                byte[] key = encodeKey(cellId, entry.keyBytes());
                byte[] value = ByteBuffer.allocate(Long.BYTES).putLong(entry.amount()).array();
                db.put(txn, key, value);
            }

            txn.commit();
        }
    }

    /** Os 16 bytes de prefixo (só o UUID) usados por {@link #loadAll} e {@link #replaceAll}. */
    private static byte[] uuidPrefix(UUID cellId) {
        return ByteBuffer.allocate(16)
                .putLong(cellId.getMostSignificantBits())
                .putLong(cellId.getLeastSignificantBits())
                .array();
    }

    private static boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Monta a chave composta armazenada no LMDB: 16 bytes de UUID (prefixo) + bytes do AEKey.
     * Manter o UUID como prefixo (em vez de sufixo) é o que permite, no futuro, escanear com um
     * cursor todas as entradas de uma célula (LMDB ordena chaves lexicograficamente por padrão).
     */
    private static byte[] encodeKey(UUID cellId, byte[] keyBytes) {
        ByteBuffer buf = ByteBuffer.allocate(16 + keyBytes.length);
        buf.putLong(cellId.getMostSignificantBits());
        buf.putLong(cellId.getLeastSignificantBits());
        buf.put(keyBytes);
        return buf.array();
    }

    @Override
    public void close() {
        db.close();
        env.close();
    }
}
