import { useCallback, useEffect, useState } from "react";

import {
  buscarPedido,
  criarPedido,
  ErroApi,
  listarPedidos,
  listarProdutos,
  type Pedido,
  type Produto,
} from "./api/api";
import { entrar, sair } from "./auth/auth";
import { useUsuario } from "./auth/useUsuario";
import { adicionar, type Carrinho, paraItens, quantidadeTotal, remover } from "./carrinho/carrinho";
import { CarrinhoLateral } from "./componentes/CarrinhoLateral";
import { Catalogo } from "./componentes/Catalogo";
import { MeusPedidos } from "./componentes/MeusPedidos";
import { StatusDoPedido } from "./componentes/StatusDoPedido";

function mensagemDe(erro: unknown): string {
  if (erro instanceof ErroApi && erro.status === 429 && erro.tenteEmSegundos) {
    return `${erro.message} Tente de novo em ${erro.tenteEmSegundos} s.`;
  }
  return erro instanceof Error ? erro.message : "Algo deu errado.";
}

export default function App() {
  const { carregando, usuario } = useUsuario();
  const [produtos, setProdutos] = useState<Produto[]>([]);
  const [carrinho, setCarrinho] = useState<Carrinho>({});
  const [pedidoAtual, setPedidoAtual] = useState<Pedido | null>(null);
  const [pedidos, setPedidos] = useState<Pedido[]>([]);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  const recarregarProdutos = useCallback(() => {
    listarProdutos().then(setProdutos).catch((e) => setErro(mensagemDe(e)));
  }, []);

  useEffect(recarregarProdutos, [recarregarProdutos]);

  useEffect(() => {
    if (usuario) listarPedidos().then(setPedidos).catch(() => {});
  }, [usuario]);

  // Acompanha o pedido ate o fim da saga, consultando a cada segundo.
  const finalizado = pedidoAtual?.status === "CONFIRMADO" || pedidoAtual?.status === "CANCELADO";
  useEffect(() => {
    if (!pedidoAtual || finalizado) return;
    const id = pedidoAtual.id;
    const intervalo = setInterval(() => {
      buscarPedido(id)
        .then((p) => {
          setPedidoAtual(p);
          if (p.status === "CONFIRMADO" || p.status === "CANCELADO") {
            recarregarProdutos();
            listarPedidos().then(setPedidos).catch(() => {});
          }
          // A devolucao dos itens (compensacao) acontece depois do cancelamento:
          // o PedidoCancelado ainda passa pelo outbox e pelo Kafka ate o estoque.
          // Recarrega o catalogo mais duas vezes para mostrar o estoque de volta.
          if (p.status === "CANCELADO") {
            for (const espera of [1500, 4000]) setTimeout(recarregarProdutos, espera);
          }
        })
        .catch((e) => setErro(mensagemDe(e)));
    }, 1000);
    return () => clearInterval(intervalo);
  }, [pedidoAtual, finalizado, recarregarProdutos]);

  async function finalizar() {
    setErro(null);
    setEnviando(true);
    try {
      const pedido = await criarPedido(paraItens(carrinho));
      setPedidoAtual(pedido);
      setCarrinho({});
    } catch (e) {
      setErro(mensagemDe(e));
    } finally {
      setEnviando(false);
    }
  }

  async function abrir(id: string) {
    try {
      setPedidoAtual(await buscarPedido(id));
    } catch (e) {
      setErro(mensagemDe(e));
    }
  }

  const email = usuario?.profile.email;

  return (
    <div className="min-h-screen">
      <header className="border-b border-borda bg-painel">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <span className="text-xl font-bold tracking-tight">
            🛍️ Bazar
          </span>
          <div className="flex items-center gap-4 text-sm">
            {quantidadeTotal(carrinho) > 0 && (
              <span className="rounded-full bg-destaque/10 px-2.5 py-0.5 text-destaque-forte">
                {quantidadeTotal(carrinho)} no carrinho
              </span>
            )}
            {carregando ? null : usuario ? (
              <>
                <span className="hidden text-suave sm:inline">{email}</span>
                <button type="button" onClick={() => void sair()} className="text-suave hover:text-texto">
                  Sair
                </button>
              </>
            ) : (
              <button
                type="button"
                onClick={() => void entrar()}
                className="rounded-lg bg-destaque px-3 py-1.5 font-medium text-white hover:bg-destaque-forte"
              >
                Entrar ou criar conta
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          {erro && (
            <p role="alert" className="rounded-xl bg-perigo/10 px-4 py-3 text-sm text-perigo">
              {erro}
            </p>
          )}
          <Catalogo produtos={produtos} carrinho={carrinho} aoAdicionar={(p) => setCarrinho((c) => adicionar(c, p))} />
        </div>
        <aside className="space-y-6">
          {pedidoAtual && <StatusDoPedido pedido={pedidoAtual} />}
          <CarrinhoLateral
            carrinho={carrinho}
            produtos={produtos}
            logado={Boolean(usuario)}
            enviando={enviando}
            aoRemover={(id) => setCarrinho((c) => remover(c, id))}
            aoAdicionar={(p) => setCarrinho((c) => adicionar(c, p))}
            aoFinalizar={() => void finalizar()}
            aoEntrar={() => void entrar()}
          />
          {usuario && <MeusPedidos pedidos={pedidos} aoAbrir={(id) => void abrir(id)} />}
        </aside>
      </main>
    </div>
  );
}
