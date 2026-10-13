import { describe, expect, it } from "vitest";

import { type EventoSSE, lerEventos } from "./sse";

function fluxo(...pedacos: string[]): ReadableStream<Uint8Array> {
  const codificador = new TextEncoder();
  return new ReadableStream({
    start(controle) {
      for (const p of pedacos) controle.enqueue(codificador.encode(p));
      controle.close();
    },
  });
}

async function todos(corpo: ReadableStream<Uint8Array>): Promise<EventoSSE[]> {
  const lidos: EventoSSE[] = [];
  for await (const e of lerEventos(corpo)) lidos.push(e);
  return lidos;
}

describe("lerEventos", () => {
  it("le eventos inteiros", async () => {
    const lidos = await todos(fluxo('event: texto\ndata: {"texto":"Oi"}\n\nevent: fim\ndata: {}\n\n'));
    expect(lidos).toEqual([
      { evento: "texto", dados: '{"texto":"Oi"}' },
      { evento: "fim", dados: "{}" },
    ]);
  });

  it("junta um evento partido em varios pedacos de rede", async () => {
    const lidos = await todos(fluxo("ev", "ent: tex", 'to\ndata: {"tex', 'to":"Olá"}', "\n", "\n"));
    expect(lidos).toEqual([{ evento: "texto", dados: '{"texto":"Olá"}' }]);
  });

  it("aceita \\r\\n, inclusive com o \\r e o \\n em pedacos diferentes", async () => {
    const lidos = await todos(fluxo("event: fim\r", "\ndata: {}\r\n\r", "\n"));
    expect(lidos).toEqual([{ evento: "fim", dados: "{}" }]);
  });

  it("ignora comentarios de keep-alive e junta linhas de dados", async () => {
    const lidos = await todos(fluxo(": ping\n\ndata: linha 1\ndata: linha 2\n\n"));
    expect(lidos).toEqual([{ evento: "message", dados: "linha 1\nlinha 2" }]);
  });

  it("decodifica acentos partidos no meio do byte", async () => {
    const bytes = new TextEncoder().encode('event: texto\ndata: "não"\n\n');
    const meio = bytes.indexOf(0xc3) + 1; // corta o "ã" no meio
    const corpo = new ReadableStream<Uint8Array>({
      start(controle) {
        controle.enqueue(bytes.slice(0, meio));
        controle.enqueue(bytes.slice(meio));
        controle.close();
      },
    });
    expect(await todos(corpo)).toEqual([{ evento: "texto", dados: '"não"' }]);
  });
});
