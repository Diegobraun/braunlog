# ADR 0001 — I/O posicional com FileChannel em vez de mmap

- **Status**: aceita
- **Data**: 2026-08-15
- **Contexto**: fase 1

## Contexto

O braunlog precisa ler e escrever segmentos com um escritor e varios leitores
concorrentes. Ha duas familias de solucao no JDK: `MappedByteBuffer` (mmap) e
chamadas posicionais de `FileChannel` (`read(buffer, posicao)` /
`write(buffer, posicao)`).

mmap e tentador: o Kafka usa mmap para os arquivos de indice e a leitura vira
acesso a memoria, sem syscall por registro.

## Decisao

Usar **I/O posicional com `FileChannel`** em todos os caminhos, inclusive no
indice esparso da fase 2.

## Motivos

1. **Unmap nao existe de forma suportada.** Um `MappedByteBuffer` so e liberado
   quando o coletor de lixo decide finalizar o buffer. Ate la o arquivo continua
   mapeado e, no Windows, nao pode ser deletado nem truncado. Retencao e
   compactacao — fases 4 e 5 — precisam exatamente disso: deletar e trocar
   arquivos. A saida comum e chamar `sun.misc.Unsafe.invokeCleaner`, o que exige
   `--add-opens`, quebra entre versoes e some do relatorio de "zero dependencia"
   sem sumir do risco.
2. **Truncar e a operacao central da recuperacao.** `FileChannel.truncate` sobre
   um arquivo mapeado tem comportamento dependente de plataforma. Recuperacao e
   a parte do projeto que mais precisa ser previsivel.
3. **Falha de I/O vira `IOException`, nao sinal do sistema.** Com mmap, um erro
   de leitura em disco chega como `SIGBUS`, que derruba a JVM inteira. Com
   `FileChannel`, o erro e um objeto que sobe pela pilha e pode ser tratado.
4. **`read(buffer, posicao)` nao mexe na posicao do canal.** Varios leitores
   compartilhariam a posicao do canal se usassem `read(buffer)`; com a variante
   posicional, cada leitor carrega a propria posicao e nenhum lock e necessario.
5. **O custo esperado e menor do que parece.** O gargalo de um commit log e o
   `fsync`, nao o `read`. Leitura sequencial de arquivo recem-escrito vem do
   page cache do sistema operacional em qualquer das duas abordagens.

## Consequencias

- Uma syscall por registro lido, hoje. A fase 5 mede isso; se doer, o remedio e
  um buffer de leitura maior no `Varredor`, nao mmap.
- Sem `--add-opens`, sem `Unsafe`, sem flag de JVM para rodar a biblioteca.
- O projeto continua explicavel: quem le o codigo ve exatamente quando o dado sai
  do disco.

## Alternativa recusada

mmap apenas para os arquivos de indice, que sao pequenos e nunca truncados em
operacao normal. Recusada porque introduziria duas disciplinas de I/O no mesmo
nucleo — e o valor deste projeto e caber na cabeca de uma pessoa.
