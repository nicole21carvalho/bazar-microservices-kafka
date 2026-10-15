import { type FormEvent, useEffect, useRef, useState } from "react";

import { confirmar, conversar, type EventoAgente, type ItemProposto } from "../agente/agente";
import type { Produto } from "../api/api";
import { reais } from "../carrinho/carrinho";

type Mensagem = { autor: "cliente" | "atendente"; texto: string };

// O limite de aprovação do serviço de pagamentos (LIMITE_APROVACAO). O aviso é do cartão, e
// não do modelo: nas avaliações, nenhum modelo avisou sozinho.
const LIMITE_DO_PAGAMENTO = 1000;
type Pendente = { itens: ItemProposto[] };

const ATIVIDADES: Record<string, string> = {
  listar_produtos: "Olhando o catálogo…",
  listar_meus_pedidos: "Buscando seus pedidos…",
  consultar_pedido: "Consultando o pedido…",
  criar_pedido: "Preparando o pedido…",
};

const BOAS_VINDAS: Mensagem = {
  autor: "atendente",
  texto: "Oi! Sou o atendente virtual do Bazar. Posso consultar seus pedidos, mostrar produtos e fazer uma compra.",
};

type Props = { produtos: Produto[]; aoMudarPedidos: () => void };

/** Chat com o agente de IA (LangGraph + MCP), em nome do cliente logado. */
export function Atendimento({ produtos, aoMudarPedidos }: Props) {
  const [aberto, setAberto] = useState(false);
  const [conversa] = useState(() => crypto.randomUUID());
  const [mensagens, setMensagens] = useState<Mensagem[]>([BOAS_VINDAS]);
  const [rascunho, setRascunho] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const [atividade, setAtividade] = useState<string | null>(null);
  const [pendente, setPendente] = useState<Pendente | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const fimDaLista = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fimDaLista.current?.scrollIntoView?.({ block: "end" });
  }, [mensagens, atividade, pendente]);

  async function consumir(fluxo: AsyncGenerator<EventoAgente>) {
    setOcupado(true);
    setErro(null);
    let respondendo = false; // o texto desta resposta vai para um balao so
    try {
      for await (const evento of fluxo) {
        if (evento.tipo === "ferramenta") {
          setAtividade(ATIVIDADES[evento.nome] ?? "Consultando a loja…");
        } else if (evento.tipo === "texto") {
          setAtividade(null);
          const novo = !respondendo;
          respondendo = true;
          setMensagens((atuais) =>
            novo
              ? [...atuais, { autor: "atendente", texto: evento.texto }]
              : [...atuais.slice(0, -1), { autor: "atendente", texto: atuais[atuais.length - 1].texto + evento.texto }],
          );
        } else if (evento.tipo === "confirmacao") {
          setPendente({ itens: evento.itens });
        } else if (evento.tipo === "erro") {
          setErro(evento.mensagem);
        }
      }
    } finally {
      setOcupado(false);
      setAtividade(null);
      aoMudarPedidos();
    }
  }

  function enviar(e: FormEvent) {
    e.preventDefault();
    const texto = rascunho.trim();
    if (!texto || ocupado || pendente) return;
    setRascunho("");
    setMensagens((atuais) => [...atuais, { autor: "cliente", texto }]);
    void consumir(conversar(conversa, texto));
  }

  function decidir(aprovado: boolean) {
    setPendente(null);
    setMensagens((atuais) => [
      ...atuais,
      { autor: "cliente", texto: aprovado ? "Confirmo o pedido." : "Não, obrigado." },
    ]);
    void consumir(confirmar(conversa, aprovado));
  }

  const porId = new Map(produtos.map((p) => [p.id, p]));
  const estimado = pendente?.itens.reduce((soma, i) => soma + (porId.get(i.produtoId)?.preco ?? 0) * i.quantidade, 0);

  if (!aberto) {
    return (
      <button
        type="button"
        onClick={() => setAberto(true)}
        className="fixed right-4 bottom-4 z-20 rounded-full bg-destaque px-5 py-3 font-medium text-white shadow-lg hover:bg-destaque-forte"
      >
        💬 Atendimento
      </button>
    );
  }

  return (
    <section
      role="dialog"
      aria-label="Atendimento do Bazar"
      className="fixed inset-x-2 bottom-2 z-20 flex h-[min(560px,85vh)] flex-col rounded-2xl border border-borda bg-painel shadow-xl sm:inset-x-auto sm:right-4 sm:bottom-4 sm:w-[380px]"
    >
      <header className="flex items-center justify-between border-b border-borda px-4 py-3">
        <div>
          <p className="font-semibold">Atendimento</p>
          <p className="text-xs text-suave">Assistente com IA · age só nos seus pedidos</p>
        </div>
        <button type="button" onClick={() => setAberto(false)} aria-label="Fechar o atendimento" className="text-suave">
          ✕
        </button>
      </header>

      <div className="flex-1 space-y-3 overflow-y-auto px-4 py-3" aria-live="polite">
        {mensagens.map((m, i) => (
          <p
            key={i}
            className={`max-w-[85%] rounded-2xl px-3 py-2 text-sm whitespace-pre-wrap ${
              m.autor === "cliente" ? "ml-auto bg-destaque text-white" : "bg-fundo"
            }`}
          >
            {m.texto}
          </p>
        ))}
        {atividade && <p className="text-xs text-suave italic">{atividade}</p>}
        {pendente && (
          <div className="rounded-xl border border-destaque/40 bg-destaque/5 p-3 text-sm">
            <p className="font-medium">Confirmar este pedido?</p>
            <ul className="mt-2 space-y-1">
              {pendente.itens.map((item) => {
                const produto = porId.get(item.produtoId);
                return (
                  <li key={item.produtoId} className="flex justify-between gap-2">
                    <span>
                      {item.quantidade}× {produto?.nome ?? item.produtoId}
                    </span>
                    {produto && <span>{reais.format(produto.preco * item.quantidade)}</span>}
                  </li>
                );
              })}
            </ul>
            {estimado !== undefined && estimado > 0 && (
              <p className="mt-2 text-xs text-suave">Total estimado: {reais.format(estimado)}</p>
            )}
            {estimado !== undefined && estimado > LIMITE_DO_PAGAMENTO && (
              <p role="note" className="mt-2 rounded-lg bg-perigo/10 px-2 py-1 text-xs text-perigo">
                A operadora recusa pagamentos acima de {reais.format(LIMITE_DO_PAGAMENTO)}: este pedido vai ser
                cancelado no pagamento.
              </p>
            )}
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                onClick={() => decidir(true)}
                className="flex-1 rounded-lg bg-destaque px-3 py-1.5 font-medium text-white hover:bg-destaque-forte"
              >
                Confirmar pedido
              </button>
              <button type="button" onClick={() => decidir(false)} className="rounded-lg border border-borda px-3 py-1.5">
                Não
              </button>
            </div>
          </div>
        )}
        {erro && (
          <p role="alert" className="rounded-xl bg-perigo/10 px-3 py-2 text-sm text-perigo">
            {erro}
          </p>
        )}
        <div ref={fimDaLista} />
      </div>

      <form onSubmit={enviar} className="flex gap-2 border-t border-borda p-3">
        <input
          value={rascunho}
          onChange={(e) => setRascunho(e.target.value)}
          maxLength={2000}
          disabled={ocupado || pendente !== null}
          placeholder={pendente ? "Confirme ou recuse o pedido acima" : "Escreva sua mensagem"}
          aria-label="Mensagem para o atendimento"
          className="flex-1 rounded-lg border border-borda px-3 py-2 text-sm"
        />
        <button
          type="submit"
          disabled={ocupado || pendente !== null || !rascunho.trim()}
          className="rounded-lg bg-destaque px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          Enviar
        </button>
      </form>
    </section>
  );
}
