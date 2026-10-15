import { describe, expect, it } from "vitest";

import { itensPropostos } from "./agente";

describe("itensPropostos", () => {
  it("lê os itens no formato da ferramenta criar_pedido (produtoId)", () => {
    expect(itensPropostos({ itens: [{ produtoId: "cafe-especial", quantidade: 2 }] })).toEqual([
      { produtoId: "cafe-especial", quantidade: 2 },
    ]);
  });

  it("descarta o que não é item válido em vez de mostrar 'undefined' no cartão", () => {
    expect(
      itensPropostos({
        itens: [
          { produto_id: "cafe-especial", quantidade: 1 }, // formato errado
          { produtoId: "caneca", quantidade: 0 },
          { produtoId: "caneca", quantidade: 1.5 },
          null,
          { produtoId: "mouse", quantidade: 1 },
        ],
      }),
    ).toEqual([{ produtoId: "mouse", quantidade: 1 }]);
  });

  it("sem itens, lista vazia", () => {
    expect(itensPropostos(undefined)).toEqual([]);
    expect(itensPropostos({ itens: "x" })).toEqual([]);
  });
});
