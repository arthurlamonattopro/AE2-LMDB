package com.example.ae2lmdb.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Configurações do addon (Fase 4 do TODO.md).
 *
 * <p>Todos os valores expostos aqui são lidos a partir de {@code config/ae2lmdb-common.toml}
 * (gerado automaticamente no primeiro run), e podem ser alterados sem recompilar — só reiniciar
 * o jogo/servidor.</p>
 *
 * <p><b>Decisão de balanceamento</b> (ver TODO.md, Fase 4 — "Definir modelo de custo/capacidade"):
 * o LMDB escala muito melhor que NBT-puro (a motivação inteira do addon), então os defaults são
 * deliberadamente generosos em comparação com a maior célula nativa da AE2 (256k = 2 MiB /
 * 63 tipos). Ajuste conforme o balanceamento do seu modpack.</p>
 */
public final class ModConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final Common COMMON = new Common(BUILDER);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ModConfig() {
    }

    public static Common common() {
        return COMMON;
    }

    /**
     * Configurações comuns (cliente e servidor). As células de armazenamento são server-side
     * por natureza, então não há motivo para dividir em client/server neste momento.
     */
    public static final class Common {

        /**
         * Capacidade total em bytes por célula. Como o conteúdo vive no LMDB (não na NBT do
         * ItemStack), esse valor é só um <i>cap</i> lógico imposto por
         * {@code DatabaseStorageCell.insert} — não tem relação direta com o tamanho do arquivo
         * LMDB em disco (que cresce sob demanda até {@link #lmdbMapSizeBytes}).
         *
         * <p>Default: {@code 67108864} = 64 MiB — equivalente a 256 células nativas de 256k.
         * O "bytes per type" (abaixo) ainda desconta desse total, no mesmo modelo de custo da
         * AE2 nativa, para o status {@code TYPES_FULL}/~{@code FULL} fazer sentido.</p>
         */
        public final ForgeConfigSpec.IntValue bytesPerCell;

        /**
         * Bytes consumidos por cada tipo distinto armazenado (overhead por chave, igual ao
         * modelo nativo da AE2). Esse valor é descontado de {@link #bytesPerCell}.
         *
         * <p>Default: {@code 8} bytes — menor que o {@code 8} da célula 256k nativa, refletindo
         * que o overhead real por entrada no LMDB (chave serializada + 8 bytes de valor) já é
         * razoavelmente próximo disso. Ajuste se profiling mostrar oportunidade.</p>
         */
        public final ForgeConfigSpec.IntValue bytesPerType;

        /**
         * Número máximo de tipos distintos por célula. Esse é o cap mais agressivo: mesmo com
         * bytes sobrando no total, uma célula não pode ultrapassar esse número de chaves
         * diferentes. {@code 0} = ilimitado (cap só por {@link #bytesPerCell}).
         *
         * <p>Default: {@code 0} (ilimitado) — um dos pontos fortes do LMDB é justamente escalar
         * para muitos tipos; impor um cap artificial iria contra a motivação do addon. Ajuste
         * para um valor finito (ex.: 4096) se quiser forçar paridade comportamental com as
         * cells nativas.</p>
         */
        public final ForgeConfigSpec.IntValue totalTypes;

        /**
         * Drenagem passiva de energia por célula ativa, em AE/tick. Repassado para
         * {@code DatabaseStorageCell#getIdleDrain}. Default {@code 1.0} — entre a cell 1k (0.5)
         * e a 256k (2.0) nativas, refletindo que a nossa é "grande" mas não criativa.
         */
        public final ForgeConfigSpec.DoubleValue idleDrain;

        /**
         * Intervalo do flush periódico do {@code CellCacheRegistry}, em segundos. O flush também
         * dispara no evento de save do mundo e ao desmontar a célula da rede; este timer é só
         * um safety net para o caso de nenhum desses dois eventos disparar por muito tempo.
         *
         * <p>Default: {@code 30} segundos — equilíbrio entre "não sobrecarregar o disco" e
         * "não perder muita coisa se o servidor crashar entre saves".</p>
         */
        public final ForgeConfigSpec.IntValue flushIntervalSeconds;

        /**
         * Tamanho máximo (virtual) do mapa de memória do LMDB, em bytes. LMDB reserva esse
         * espaço de endereçamento antecipadamente, mas o arquivo em disco só cresce sob
         * demanda (é esparso) — não aloca tudo isso de disco de cara.
         *
         * <p>Default: {@code 1073741824} = 1 GiB. Reabrir o {@code Env} é necessário para
         * mudar esse valor em runtime; o servidor precisa ser reiniciado.</p>
         */
        public final ForgeConfigSpec.LongValue lmdbMapSizeBytes;

        private Common(ForgeConfigSpec.Builder b) {
            b.push("cell");
            bytesPerCell = b.comment("Capacidade total em bytes por célula (default 64 MiB).")
                    .defineInRange("bytesPerCell", 64 * 1024 * 1024, 1, Integer.MAX_VALUE);
            bytesPerType = b.comment("Bytes consumidos por cada tipo distinto armazenado (default 8).")
                    .defineInRange("bytesPerType", 8, 0, Integer.MAX_VALUE);
            totalTypes = b.comment("Número máximo de tipos distintos por célula (0 = ilimitado).")
                    .defineInRange("totalTypes", 0, 0, Integer.MAX_VALUE);
            idleDrain = b.comment("Drenagem passiva de energia, em AE/tick (default 1.0).")
                    .defineInRange("idleDrain", 1.0, 0.0, Double.MAX_VALUE);
            b.pop();

            b.push("storage");
            flushIntervalSeconds = b.comment("Intervalo do flush periódico do cache, em segundos (default 30).")
                    .defineInRange("flushIntervalSeconds", 30, 1, Integer.MAX_VALUE);
            lmdbMapSizeBytes = b.comment("Tamanho máximo virtual do mapa LMDB, em bytes (default 1 GiB).")
                    .defineInRange("lmdbMapSizeBytes", 1L << 30, 1024L, Long.MAX_VALUE);
            b.pop();
        }
    }
}
