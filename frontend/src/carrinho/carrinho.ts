import type { Produto } from "../api/api";

/** produtoId -> quantidade. Imutavel: cada operacao devolve um carrinho novo. */
export type Carrinho = Readonly<Record<string, number>>;

// Os mesmos limites que o servico de pedidos valida; aqui so para avisar antes.
export const MAX_POR_ITEM = 99;
export const MAX_ITENS = 20;

export function adicionar(carrinho: Carrinho, produto: Produto): Carrinho {
  const atual = carrinho[produto.id] ?? 0;
  const novoProduto = atual === 0;
  if (novoProduto && Object.keys(carrinho).length >= MAX_ITENS) return carrinho;
  const quantidade = Math.min(atual + 1, MAX_POR_ITEM, produto.disponivel);
  if (quantidade === atual) return carrinho;
  return { ...carrinho, [produto.id]: quantidade };
}

export function remover(carrinho: Carrinho, produtoId: string): Carrinho {
  const atual = carrinho[produtoId] ?? 0;
  if (atual <= 1) {
    const { [produtoId]: _removido, ...resto } = carrinho;
    return resto;
  }
  return { ...carrinho, [produtoId]: atual - 1 };
}

/**
 * Total estimado com os precos que a tela conhece. O valor cobrado de verdade
 * e calculado pelo servico de estoque, com o preco do catalogo: o cliente nunca
 * informa preco.
 */
export function totalEstimado(carrinho: Carrinho, produtos: Produto[]): number {
  return produtos.reduce((soma, p) => soma + (carrinho[p.id] ?? 0) * p.preco, 0);
}

export function paraItens(carrinho: Carrinho) {
  return Object.entries(carrinho).map(([produtoId, quantidade]) => ({ produtoId, quantidade }));
}

export function quantidadeTotal(carrinho: Carrinho): number {
  return Object.values(carrinho).reduce((soma, q) => soma + q, 0);
}

export const reais = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
