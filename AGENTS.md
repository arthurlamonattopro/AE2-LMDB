# AGENTS.md

Guia para agentes de IA (Claude Code, Copilot, etc.) trabalhando neste repositório. Leia isto antes de escrever ou modificar qualquer código.

## Contexto do projeto

Addon Forge para Applied Energistics 2 (AE2) que troca o backend de armazenamento das Storage Cells de NBT-puro para LMDB. Ver `README.md` para o "porquê"; este arquivo é sobre o "como codar".

## Stack fixa (não mudar sem discutir)

- Minecraft **1.20.1**
- Forge (não Fabric/NeoForge — o projeto é especificamente Forge)
- Java **17**
- AE2 como dependência de API (não faz fork do AE2, é um addon separado)
- `lmdbjava` para o banco (não trocar por SQLite/MapDB sem atualizar README/decisão de arquitetura)

## Estrutura de diretórios (convenção)

```
src/main/java/<grupo>/ae2lmdb/
├── AE2LmdbMod.java              # classe principal do mod
├── item/
│   └── DatabaseStorageCellItem.java
├── storage/
│   ├── LmdbBackedStorage.java   # implementa MEStorage
│   ├── CellCache.java           # camada de cache em memória
│   ├── CellCacheRegistry.java   # dono do LmdbManager do save atual + caches ativos + flush assíncrono
│   └── LmdbManager.java         # abre/fecha env LMDB, um por save
├── serialization/
│   └── AEKeyCodec.java          # serializa AEKey <-> bytes para o LMDB
└── event/
    └── WorldSaveHandler.java    # flush do cache em save/unload
```

## Decisões arquiteturais que DEVEM ser respeitadas

1. **O item da célula nunca guarda o conteúdo, só o UUID.** Não adicionar de volta listas de item na NBT "pra facilitar" — isso reintroduz o problema original.
2. **Toda leitura/escrita de gameplay passa pelo cache em memória, nunca direto no LMDB.** Acesso direto ao disco a cada `insert`/`extract` mata a performance no tick do servidor.
3. **Um ambiente LMDB (`Env`) por save do mundo**, guardado em `data/ae2lmdb/` dentro da pasta do save (usar `LevelResource`, nunca um path global do jogo).
4. **Flush assíncrono**, nunca bloqueante na thread principal do servidor. Usar uma fila + thread dedicada ou `CompletableFuture` com executor próprio.
5. **Serialização de `AEKey`**: usar os helpers do próprio AE2 (`AEKey.toTagGeneric()` / `fromTagGeneric()`) como base, convertendo o `CompoundTag` resultante pra bytes via `NbtIo`. Não inventar um formato binário customizado antes de validar que o overhead da NBT é realmente um problema.
6. **Sub-databases do LMDB: uma única DB nomeada (`cells`), NÃO uma sub-DB por UUID de célula.**
   O LMDB exige declarar o número máximo de DBs nomeadas (`maxDbs`) na abertura do `Env`, então uma
   sub-DB por célula exigiria saber de antemão quantas células existem (ou reabrir o `Env` toda vez
   que uma célula nova é criada) — inviável num addon onde o jogador pode craftar células a qualquer
   momento. A alternativa adotada é: **uma única DB, com chave composta = 16 bytes do UUID da célula
   + bytes do `AEKey` serializado** (`LmdbManager.encodeKey`). Isso também permite, no futuro, escanear
   com um cursor todas as entradas de uma célula específica via prefixo do UUID, já que o LMDB ordena
   chaves lexicograficamente por padrão.
7. **`LmdbManager` usa `byte[]` + `ByteArrayProxy.PROXY_BA`, NÃO `ByteBuffer` + `ByteBufferProxy`.**
   O lmdbjava, com `ByteBufferProxy` (mesmo pedindo a variante "segura" `PROXY_SAFE`), carrega uma
   classe interna que tenta acessar reflexivamente o campo `java.nio.Buffer.address`; a partir do
   JDK 16+ isso lança `InaccessibleObjectException` a menos que a JVM rode com
   `--add-opens java.base/java.nio=ALL-UNNAMED`. Como este addon roda na JVM de terceiros
   (jogador/admin do servidor, que não vamos pedir pra configurar flags), usar `byte[]` evita esse
   problema por completo, ao custo de uma cópia extra de array. Não trocar de volta pra
   `ByteBuffer`/`ByteBufferProxy` sem resolver esse problema de JPMS primeiro.

## O que NÃO fazer

- Não modificar/forkar classes do AE2 diretamente — este é um addon que usa a API pública dele.
- Não usar SQLite, MapDB ou qualquer outro backend sem atualizar a decisão em `README.md` primeiro.
- Não fazer I/O de disco síncrono em métodos chamados no tick do servidor (`insert`, `extract`, `getAvailableStacks`).
- Não esquecer do problema de duplicação de UUID ao mexer no fluxo de criação/clonagem do item (ver seção "Limitações conhecidas" no README e o item correspondente em `TODO.md`).

## Build & testes

```bash
./gradlew build          # build completo
./gradlew runServer      # sobe um servidor de teste local com o mod + AE2
```

Ambiente de teste precisa do jar do AE2 1.20.1 na pasta `run/mods/` (ou configurado como dependência de runtime no `build.gradle`).

## Referências de API

- Repositório AE2: https://github.com/AppliedEnergistics/Applied-Energistics-2
- Guia de addon/API (adaptar pra versão 1.20.1): https://guide.appliedenergistics.org/1.20.1/api
- Javadoc: https://appliedenergistics.org/javadoc/
- lmdbjava: https://github.com/lmdbjava/lmdbjava

## Estilo de código

- Seguir convenções padrão Java/Forge (Mojang mappings).
- Nomes de classes/métodos em inglês (padrão do ecossistema Forge/AE2), comentários podem ser em português ou inglês — manter consistência dentro de um mesmo arquivo.
