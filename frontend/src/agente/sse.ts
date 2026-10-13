export type EventoSSE = { evento: string; dados: string };

/**
 * Le um fluxo text/event-stream vindo de um fetch.
 *
 * O EventSource do navegador nao serve aqui: ele so faz GET e nao envia o
 * cabecalho Authorization. Os eventos podem chegar partidos entre pacotes de
 * rede (ou varios num pacote so); este leitor junta os pedacos antes de ler.
 */
export async function* lerEventos(corpo: ReadableStream<Uint8Array>): AsyncGenerator<EventoSSE> {
  const leitor = corpo.getReader();
  const decodificador = new TextDecoder();
  let pendente = "";
  try {
    for (;;) {
      const { value, done } = await leitor.read();
      pendente += done ? decodificador.decode() : decodificador.decode(value, { stream: true });
      // Fim de linha pode ser \r\n, \r ou \n. Um \r no fim do pedaco pode ser a
      // metade de um \r\n: fica esperando o proximo pedaco.
      const segura = !done && pendente.endsWith("\r");
      const normalizado = (segura ? pendente.slice(0, -1) : pendente).replace(/\r\n?/g, "\n");
      const blocos = normalizado.split("\n\n");
      pendente = (done ? "" : blocos.pop() ?? "") + (segura ? "\r" : "");
      for (const bloco of blocos) {
        const evento = interpretar(bloco);
        if (evento) yield evento;
      }
      if (done) return;
    }
  } finally {
    leitor.releaseLock();
  }
}

function interpretar(bloco: string): EventoSSE | null {
  let evento = "message";
  const dados: string[] = [];
  for (const linha of bloco.split("\n")) {
    if (!linha || linha.startsWith(":")) continue; // linha vazia ou comentario (keep-alive)
    const separador = linha.indexOf(":");
    const campo = separador < 0 ? linha : linha.slice(0, separador);
    const valor = separador < 0 ? "" : linha.slice(separador + 1).replace(/^ /, "");
    if (campo === "event") evento = valor;
    else if (campo === "data") dados.push(valor);
  }
  return dados.length ? { evento, dados: dados.join("\n") } : null;
}
