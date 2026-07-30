package com.example.ae2lmdb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Teste isolado da Fase 1 (ver TODO.md): escreve e lê um par "AEKey -&gt; long" no LMDB
 * sem envolver o Minecraft — aqui, os "bytes de AEKey" são só um array arbitrário, porque o
 * {@link LmdbManager} propositalmente não sabe (nem precisa saber) o que tem dentro deles;
 * essa é exatamente a fronteira que separa esta classe de {@link com.example.ae2lmdb.serialization.AEKeyCodec}.
 *
 * <p>Rode com {@code ./gradlew test}. Diferente dos testes de item/gameplay (que vão precisar
 * de {@code runServer} com o AE2 presente), este roda como JVM pura — não inicializa nenhuma
 * registry do Minecraft.</p>
 */
class LmdbManagerTest {

    // Um "AEKey serializado" de mentirinha — na vida real viria de AEKeyCodec.encode(AEKey).
    private static final byte[] FAKE_KEY_BYTES = "fake-aekey-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void escreveELeUmParChaveValor(@TempDir Path tempDir) {
        UUID cellId = UUID.randomUUID();

        try (LmdbManager manager = LmdbManager.open(tempDir)) {
            manager.put(cellId, FAKE_KEY_BYTES, 42L);

            Long lido = manager.get(cellId, FAKE_KEY_BYTES);

            assertEquals(42L, lido);
        }
    }

    @Test
    void chaveInexistenteRetornaNull(@TempDir Path tempDir) {
        UUID cellId = UUID.randomUUID();

        try (LmdbManager manager = LmdbManager.open(tempDir)) {
            assertNull(manager.get(cellId, FAKE_KEY_BYTES));
        }
    }

    @Test
    void celulasDiferentesNaoSeMisturam(@TempDir Path tempDir) {
        UUID celulaA = UUID.randomUUID();
        UUID celulaB = UUID.randomUUID();

        try (LmdbManager manager = LmdbManager.open(tempDir)) {
            manager.put(celulaA, FAKE_KEY_BYTES, 1L);
            manager.put(celulaB, FAKE_KEY_BYTES, 2L);

            assertEquals(1L, manager.get(celulaA, FAKE_KEY_BYTES));
            assertEquals(2L, manager.get(celulaB, FAKE_KEY_BYTES));
        }
    }

    @Test
    void removePermiteQueChaveVoltaANaoExistir(@TempDir Path tempDir) {
        UUID cellId = UUID.randomUUID();

        try (LmdbManager manager = LmdbManager.open(tempDir)) {
            manager.put(cellId, FAKE_KEY_BYTES, 7L);
            manager.remove(cellId, FAKE_KEY_BYTES);

            assertNull(manager.get(cellId, FAKE_KEY_BYTES));
        }
    }

    @Test
    void dadosSobrevivemAoFecharEReabrirOAmbiente(@TempDir Path tempDir) {
        UUID cellId = UUID.randomUUID();

        try (LmdbManager manager = LmdbManager.open(tempDir)) {
            manager.put(cellId, FAKE_KEY_BYTES, 99L);
        }

        try (LmdbManager reaberto = LmdbManager.open(tempDir)) {
            assertEquals(99L, reaberto.get(cellId, FAKE_KEY_BYTES));
        }
    }
}
