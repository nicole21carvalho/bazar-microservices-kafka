import type { Produto } from "../api/api";
import { type Carrinho, reais, totalEstimado } from "../carrinho/carrinho";

type Props = {
  carrinho: Carrinho;
  produtos: Produto[];
  logado: boolean;
  enviando: boolean;
  aoRemover: (produtoId: string) => void;
  aoAdicionar: (p: Produto) => void;
  aoFinalizar: () => void;
  aoEntrar: () => void;
};

export function CarrinhoLateral(props: Props) {
  const { carrinho, produtos, logado, enviando } = props;
  const itens = produtos.filter((p) => (carrinho[p.id] ?? 0) > 0);

  return (
    <section aria-labelledby="titulo-carrinho" className="rounded-2xl border border-borda bg-painel p-5">
      <h2 id="titulo-carrinho" className="font-semibold">
        Carrinho
      </h2>
      {itens.length === 0 ? (
        <p className="mt-3 text-sm text-suave">Vazio. Escolha algum produto.</p>
      ) : (
        <>
          <ul className="mt-3 divide-y divide-borda">
            {itens.map((p) => (
              <li key={p.id} className="flex items-center justify-between gap-2 py-2 text-sm">
                <span className="min-w-0 flex-1 truncate">{p.nome}</span>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => props.aoRemover(p.id)}
                    aria-label={`Tirar um ${p.nome}`}
                    className="h-7 w-7 rounded-lg border border-borda hover:bg-fundo"
                  >
                    −
                  </button>
                  <span aria-label="quantidade" className="w-5 text-center">
                    {carrinho[p.id]}
                  </span>
                  <button
                    type="button"
                    onClick={() => props.aoAdicionar(p)}
                    aria-label={`Mais um ${p.nome}`}
                    className="h-7 w-7 rounded-lg border border-borda hover:bg-fundo"
                  >
                    +
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p className="mt-3 flex justify-between border-t border-borda pt-3 font-semibold">
            <span>Total estimado</span>
            <span>{reais.format(totalEstimado(carrinho, produtos))}</span>
          </p>
          {logado ? (
            <button
              type="button"
              onClick={props.aoFinalizar}
              disabled={enviando}
              className="mt-4 w-full rounded-xl bg-destaque px-4 py-2.5 font-medium text-white transition hover:bg-destaque-forte disabled:opacity-60"
            >
              {enviando ? "Enviando…" : "Finalizar pedido"}
            </button>
          ) : (
            <button
              type="button"
              onClick={props.aoEntrar}
              className="mt-4 w-full rounded-xl border border-destaque px-4 py-2.5 font-medium text-destaque-forte hover:bg-destaque/10"
            >
              Entre para finalizar
            </button>
          )}
        </>
      )}
    </section>
  );
}
