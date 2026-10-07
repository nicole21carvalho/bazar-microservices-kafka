import { entrar, token } from "../auth/auth";

const API = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

export type Produto = { id: string; nome: string; preco: number; disponivel: number };

export type StatusPedido = "PENDENTE" | "AGUARDANDO_PAGAMENTO" | "CONFIRMADO" | "CANCELADO";

export type Pedido = {
  id: string;
  status: StatusPedido;
  total: number | null;
  motivo: string | null;
  itens: { produtoId: string; quantidade: number }[];
  criadoEm: string;
  atualizadoEm: string;
};

export class ErroApi extends Error {
  readonly status: number;
  readonly tenteEmSegundos?: number;

  constructor(status: number, message: string, tenteEmSegundos?: number) {
    super(message);
    this.status = status;
    this.tenteEmSegundos = tenteEmSegundos;
  }
}

const MENSAGENS: Record<number, string> = {
  403: "Você não tem permissão para isso.",
  404: "Não encontrado.",
  413: "Pedido grande demais.",
  429: "Muitas tentativas seguidas.",
};

async function requisitar(caminho: string, opcoes: RequestInit = {}): Promise<Response> {
  const acesso = await token();
  const resposta = await fetch(API + caminho, {
    ...opcoes,
    headers: {
      ...(acesso ? { Authorization: `Bearer ${acesso}` } : {}),
      ...opcoes.headers,
    },
  });
  if (resposta.status === 401) {
    // Sessao acabou: volta ao login e depois para a mesma tela.
    await entrar();
    throw new ErroApi(401, "Sua sessão terminou. Entre de novo.");
  }
  if (!resposta.ok) {
    let detalhe: string | undefined;
    try {
      // Os servicos respondem erro no formato RFC 9457 (problem+json).
      detalhe = (await resposta.json()).detail;
    } catch {
      // corpo vazio ou nao-JSON
    }
    const espera = Number(resposta.headers.get("Retry-After")) || undefined;
    throw new ErroApi(resposta.status, detalhe ?? MENSAGENS[resposta.status] ?? "Algo deu errado.", espera);
  }
  return resposta;
}

export async function listarProdutos(): Promise<Produto[]> {
  return (await requisitar("/api/produtos")).json();
}

export async function criarPedido(itens: { produtoId: string; quantidade: number }[]): Promise<Pedido> {
  const resposta = await requisitar("/api/pedidos", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ itens }),
  });
  return resposta.json();
}

export async function buscarPedido(id: string): Promise<Pedido> {
  return (await requisitar(`/api/pedidos/${encodeURIComponent(id)}`)).json();
}

export async function listarPedidos(): Promise<Pedido[]> {
  return (await requisitar("/api/pedidos")).json();
}
