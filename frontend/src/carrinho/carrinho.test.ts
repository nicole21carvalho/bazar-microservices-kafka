import { describe, expect, it } from "vitest";

import type { Produto } from "../api/api";
import { adicionar, MAX_ITENS, paraItens, remover, totalEstimado } from "./carrinho";

const cafe: Produto = { id: "cafe", nome: "Café", preco: 42.9, disponivel: 3 };
const caneca: Produto = { id: "caneca", nome: "Caneca", preco: 39.9, disponivel: 80 };

describe("carrinho", () => {
  it("soma quantidades e calcula o total estimado", () => {
    let c = adicionar({}, cafe);
    c = adicionar(c, cafe);
    c = adicionar(c, caneca);

    expect(c).toEqual({ cafe: 2, caneca: 1 });
    expect(totalEstimado(c, [cafe, caneca])).toBeCloseTo(125.7);
    expect(paraItens(c)).toEqual([
      { produtoId: "cafe", quantidade: 2 },
      { produtoId: "caneca", quantidade: 1 },
    ]);
  });

  it("nao passa do estoque disponivel", () => {
    let c = {};
    for (let i = 0; i < 10; i++) c = adicionar(c, cafe);

    expect(c).toEqual({ cafe: 3 });
  });

  it("remover a ultima unidade tira o produto do carrinho", () => {
    expect(remover({ cafe: 1, caneca: 2 }, "cafe")).toEqual({ caneca: 2 });
    expect(remover({ caneca: 2 }, "caneca")).toEqual({ caneca: 1 });
  });

  it("respeita o limite de produtos diferentes por pedido", () => {
    const cheio = Object.fromEntries(Array.from({ length: MAX_ITENS }, (_, i) => [`p${i}`, 1]));

    expect(adicionar(cheio, caneca)).toBe(cheio);
  });

  it("nunca altera o carrinho original", () => {
    const original = Object.freeze({ cafe: 1 });

    adicionar(original, cafe);
    remover(original, "cafe");

    expect(original).toEqual({ cafe: 1 });
  });
});
