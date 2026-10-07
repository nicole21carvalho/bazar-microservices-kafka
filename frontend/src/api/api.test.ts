import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../auth/auth", () => ({
  token: vi.fn(async () => "token-de-teste"),
  entrar: vi.fn(async () => {}),
}));

import { entrar } from "../auth/auth";
import { criarPedido, ErroApi, listarProdutos } from "./api";

function resposta(status: number, corpo: unknown, cabecalhos: Record<string, string> = {}) {
  return new Response(corpo === undefined ? null : JSON.stringify(corpo), {
    status,
    headers: { "Content-Type": "application/json", ...cabecalhos },
  });
}

describe("api", () => {
  const fetchFalso = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchFalso);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    fetchFalso.mockReset();
  });

  it("envia o token no cabecalho Authorization", async () => {
    fetchFalso.mockResolvedValue(resposta(200, []));

    await listarProdutos();

    const [, opcoes] = fetchFalso.mock.calls[0];
    expect(new Headers(opcoes?.headers).get("Authorization")).toBe("Bearer token-de-teste");
  });

  it("429 vira erro com o tempo de espera do Retry-After", async () => {
    fetchFalso.mockResolvedValue(
      resposta(429, { detail: "Muitas requisições. Tente de novo em instantes." }, { "Retry-After": "6" }),
    );

    const erro = await criarPedido([{ produtoId: "cafe", quantidade: 1 }]).catch((e) => e);

    expect(erro).toBeInstanceOf(ErroApi);
    expect(erro.status).toBe(429);
    expect(erro.tenteEmSegundos).toBe(6);
  });

  it("usa a mensagem do servico (problem+json) quando existe", async () => {
    fetchFalso.mockResolvedValue(resposta(403, { detail: "Confirme seu e-mail antes de fazer pedidos" }));

    await expect(criarPedido([])).rejects.toThrow("Confirme seu e-mail");
  });

  it("401 manda de volta para o login", async () => {
    fetchFalso.mockResolvedValue(resposta(401, undefined));

    await expect(listarProdutos()).rejects.toBeInstanceOf(ErroApi);
    expect(entrar).toHaveBeenCalled();
  });
});
