import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Pedido } from "../api/api";
import { StatusDoPedido } from "./StatusDoPedido";

const base: Pedido = {
  id: "4bf92f35-77b3-4da6-a3ce-929d0e0e4736",
  status: "PENDENTE",
  total: null,
  motivo: null,
  itens: [{ produtoId: "cafe-especial", quantidade: 2 }],
  criadoEm: "2026-10-07T12:00:00Z",
  atualizadoEm: "2026-10-07T12:00:00Z",
};

describe("StatusDoPedido", () => {
  it("acende as etapas conforme a saga avanca", () => {
    render(<StatusDoPedido pedido={{ ...base, status: "AGUARDANDO_PAGAMENTO", total: 85.8 }} />);

    expect(screen.getByText("Pedido recebido").textContent).toContain("concluído");
    expect(screen.getByText("Itens separados").textContent).toContain("concluído");
    expect(screen.getByText("Pedido confirmado").textContent).toContain("aguardando");
    expect(screen.getByText(/85,80/)).toBeTruthy();
  });

  it("pedido cancelado mostra o motivo e avisa que nada foi cobrado", () => {
    render(
      <StatusDoPedido
        pedido={{ ...base, status: "CANCELADO", motivo: "Valor acima do limite do cartão (R$ 1.000,00)" }}
      />,
    );

    expect(screen.getByRole("status").textContent).toContain("Valor acima do limite");
    expect(screen.getByRole("status").textContent).toContain("Nenhum valor foi cobrado");
  });
});
