import { token } from "../auth/auth";
import { lerEventos } from "./sse";

// Endereco do agente de atendimento (repositorio bazar-ai-agent-mcp). Vazio: o
// painel funciona sozinho, sem o chat.
const AGENTE = import.meta.env.VITE_AGENTE_URL ?? "";
export const atendimentoAtivo = AGENTE !== "";

// No formato do esquema da ferramenta criar_pedido (o mesmo da API do Bazar): produtoId.
export type ItemProposto = { produtoId: string; quantidade: number };

/** Os itens que o modelo propôs, conferidos: só entram id de texto e quantidade inteira positiva. */
export function itensPropostos(argumentos: unknown): ItemProposto[] {
  const itens = (argumentos as { itens?: unknown } | null)?.itens;
  if (!Array.isArray(itens)) return [];
  return itens.flatMap((item) => {
    const { produtoId, quantidade } = (item ?? {}) as { produtoId?: unknown; quantidade?: unknown };
    return typeof produtoId === "string" && Number.isInteger(quantidade) && (quantidade as number) > 0
      ? [{ produtoId, quantidade: quantidade as number }]
      : [];
  });
}

export type EventoAgente =
  | { tipo: "texto"; texto: string }
  | { tipo: "ferramenta"; nome: string }
  | { tipo: "confirmacao"; ferramenta: string; itens: ItemProposto[] }
  | { tipo: "erro"; mensagem: string }
  | { tipo: "fim" };

const MENSAGENS: Record<number, string> = {
  401: "Sua sessão terminou. Entre de novo no Bazar.",
  409: "Espere a resposta anterior terminar.",
  429: "Muitas mensagens seguidas. Espere um pouco.",
};

async function* enviar(caminho: string, corpo: unknown): AsyncGenerator<EventoAgente> {
  const acesso = await token();
  let resposta: Response;
  try {
    resposta = await fetch(AGENTE + caminho, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...(acesso ? { Authorization: `Bearer ${acesso}` } : {}),
      },
      body: JSON.stringify(corpo),
    });
  } catch {
    yield { tipo: "erro", mensagem: "O atendimento está fora do ar agora." };
    return;
  }
  if (!resposta.ok || !resposta.body) {
    let mensagem = MENSAGENS[resposta.status] ?? "O atendimento não respondeu.";
    try {
      const detalhe = (await resposta.json()).detail;
      if (typeof detalhe === "string") mensagem = detalhe;
    } catch {
      // corpo vazio ou nao-JSON
    }
    yield { tipo: "erro", mensagem };
    return;
  }
  for await (const { evento, dados } of lerEventos(resposta.body)) {
    const valor = JSON.parse(dados);
    if (evento === "texto") yield { tipo: "texto", texto: String(valor.texto) };
    else if (evento === "ferramenta") yield { tipo: "ferramenta", nome: String(valor.nome) };
    else if (evento === "confirmacao")
      yield { tipo: "confirmacao", ferramenta: String(valor.ferramenta), itens: itensPropostos(valor.argumentos) };
    else if (evento === "erro") yield { tipo: "erro", mensagem: String(valor.mensagem) };
    else if (evento === "fim") yield { tipo: "fim" };
  }
}

export function conversar(conversa: string, texto: string): AsyncGenerator<EventoAgente> {
  return enviar(`/api/conversas/${conversa}/mensagens`, { texto });
}

export function confirmar(conversa: string, aprovado: boolean): AsyncGenerator<EventoAgente> {
  return enviar(`/api/conversas/${conversa}/confirmacao`, { aprovado });
}
