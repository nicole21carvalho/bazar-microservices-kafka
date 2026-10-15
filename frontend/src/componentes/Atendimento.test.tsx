import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { EventoAgente } from "../agente/agente";
import type { Produto } from "../api/api";
import { Atendimento } from "./Atendimento";

const agente = vi.hoisted(() => ({ conversar: vi.fn(), confirmar: vi.fn() }));
vi.mock("../agente/agente", () => agente);

async function* eventos(...lista: EventoAgente[]): AsyncGenerator<EventoAgente> {
  for (const e of lista) yield e;
}

const produtos: Produto[] = [
  { id: "cafe-especial", nome: "Café especial 250 g", preco: 42.9, disponivel: 100 },
  { id: "monitor-27", nome: 'Monitor 27" 4K', preco: 1899, disponivel: 10 },
];

function abrir(aoMudarPedidos = vi.fn()) {
  render(<Atendimento produtos={produtos} aoMudarPedidos={aoMudarPedidos} />);
  fireEvent.click(screen.getByRole("button", { name: /Atendimento/ }));
  return aoMudarPedidos;
}

function escrever(texto: string) {
  fireEvent.change(screen.getByLabelText("Mensagem para o atendimento"), { target: { value: texto } });
  fireEvent.click(screen.getByRole("button", { name: "Enviar" }));
}

describe("Atendimento", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(cleanup);

  it("mostra a resposta em pedaços num balão só, e a atividade enquanto consulta", async () => {
    agente.conversar.mockReturnValue(
      eventos(
        { tipo: "ferramenta", nome: "consultar_pedido" },
        { tipo: "texto", texto: "Seu pedido " },
        { tipo: "texto", texto: "está confirmado." },
        { tipo: "fim" },
      ),
    );
    const aoMudarPedidos = abrir();
    escrever("Cadê meu pedido?");

    expect(await screen.findByText("Seu pedido está confirmado.")).toBeTruthy();
    expect(screen.getByText("Cadê meu pedido?")).toBeTruthy();
    expect(agente.conversar).toHaveBeenCalledWith(expect.any(String), "Cadê meu pedido?");
    await waitFor(() => expect(aoMudarPedidos).toHaveBeenCalled()); // a lista de pedidos se atualiza
  });

  it("compra só acontece depois do clique em confirmar, com nome e preço do catálogo", async () => {
    agente.conversar.mockReturnValue(
      eventos(
        { tipo: "confirmacao", ferramenta: "criar_pedido", itens: [{ produtoId: "cafe-especial", quantidade: 2 }] },
        { tipo: "fim" },
      ),
    );
    agente.confirmar.mockReturnValue(eventos({ tipo: "texto", texto: "Pedido criado!" }, { tipo: "fim" }));
    abrir();
    escrever("Quero 2 cafés");

    expect(await screen.findByText("Confirmar este pedido?")).toBeTruthy();
    expect(screen.getByText("2× Café especial 250 g")).toBeTruthy();
    expect(screen.getByText(/Total estimado: R\$\s*85,80/)).toBeTruthy();
    // Enquanto a confirmação espera, não dá para mandar outra mensagem.
    expect((screen.getByLabelText("Mensagem para o atendimento") as HTMLInputElement).disabled).toBe(true);
    expect(agente.confirmar).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Confirmar pedido" }));
    expect(await screen.findByText("Pedido criado!")).toBeTruthy();
    const [conversaDaMensagem] = agente.conversar.mock.calls[0];
    expect(agente.confirmar).toHaveBeenCalledWith(conversaDaMensagem, true); // a mesma conversa
  });

  it("pedido acima do limite da operadora mostra o aviso no cartão", async () => {
    agente.conversar.mockReturnValue(
      eventos({ tipo: "confirmacao", ferramenta: "criar_pedido", itens: [{ produtoId: "monitor-27", quantidade: 1 }] }, { tipo: "fim" }),
    );
    abrir();
    escrever("Quero o monitor");
    expect((await screen.findByRole("note")).textContent).toMatch(/recusa pagamentos acima de R\$\s*1\.000,00/);
  });

  it("pedido dentro do limite não mostra aviso", async () => {
    agente.conversar.mockReturnValue(
      eventos({ tipo: "confirmacao", ferramenta: "criar_pedido", itens: [{ produtoId: "cafe-especial", quantidade: 1 }] }, { tipo: "fim" }),
    );
    abrir();
    escrever("Quero um café");
    await screen.findByText("Confirmar este pedido?");
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("recusar avisa o agente com um não", async () => {
    agente.conversar.mockReturnValue(
      eventos({ tipo: "confirmacao", ferramenta: "criar_pedido", itens: [] }, { tipo: "fim" }),
    );
    agente.confirmar.mockReturnValue(eventos({ tipo: "fim" }));
    abrir();
    escrever("Quero comprar");
    fireEvent.click(await screen.findByRole("button", { name: "Não" }));
    expect(agente.confirmar).toHaveBeenCalledWith(expect.any(String), false);
    expect(screen.getByText("Não, obrigado.")).toBeTruthy();
  });

  it("erro do atendimento aparece como alerta", async () => {
    agente.conversar.mockReturnValue(eventos({ tipo: "erro", mensagem: "Muitas mensagens seguidas." }));
    abrir();
    escrever("oi");
    expect((await screen.findByRole("alert")).textContent).toBe("Muitas mensagens seguidas.");
  });
});
