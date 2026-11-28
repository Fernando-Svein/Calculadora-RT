/* Sistema: logo do escritório exibida no cabeçalho, na tela de login e na aba do navegador (somente administradores). */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  Hub.registrar({
    rota: 'logo',
    titulo: 'Logo do escritório',
    grupo: 'Sistema',
    perfil: 'ADMIN',
    ordem: 4,
    descricao: 'Envie a logo do escritório: ela aparece no cabeçalho, na tela de login e no ícone da aba do navegador.',
    async render(el) {
      el.innerHTML = comp.cabecalho('Logo do escritório',
        'A logo aparece no cabeçalho do menu, na tela de login e no ícone da aba do navegador, para todos os usuários.') +
        `<div class="card"><h3>Logo atual</h3><div data-previa></div></div>
        <div class="card"><h3>Enviar nova logo</h3>
          <div class="campo"><label for="arqLogo">Arquivo da logo</label>
            <input id="arqLogo" type="file" accept="image/png,image/jpeg" data-arq>
            <span class="ajuda">PNG (de preferência com fundo transparente) ou JPEG, até 2 MB. Imagens grandes são reduzidas automaticamente.
              Logos mais largas que altas ficam melhores no cabeçalho.</span></div>
          <div class="acoes u-mt-12"><button class="btn" type="button" data-enviar>Enviar logo</button>
            <button class="btn btn-perigo" type="button" data-remover>Remover logo</button></div>
        </div>`;
      const previa = el.querySelector('[data-previa]');
      const arq = el.querySelector('[data-arq]');

      async function mostrar() {
        const r = await api.get('/marca');
        const tem = r.ok && r.data.logo;
        el.querySelector('[data-remover]').hidden = !tem;
        previa.innerHTML = tem
          ? `<div class="logo-previas">
              <figure class="logo-previa"><img src="/marca/logo?v=${esc(r.data.versao)}" alt="Logo do escritório em fundo claro"><figcaption>Fundo claro</figcaption></figure>
              <figure class="logo-previa logo-previa-escura"><img src="/marca/logo?v=${esc(r.data.versao)}" alt="Logo do escritório em fundo escuro"><figcaption>Fundo escuro</figcaption></figure>
            </div>
            <p class="sutil">No modo escuro, a logo ganha uma placa branca no cabeçalho para continuar legível.</p>`
          : '<p class="sutil">Nenhuma logo cadastrada: o hub usa o ícone padrão.</p>';
        if (window.HubMarca) window.HubMarca.aplicar();
      }

      el.querySelector('[data-enviar]').onclick = async () => {
        const f = arq.files[0];
        if (!f) { Hub.toast('Escolha o arquivo da logo.', 'erro'); return; }
        if (f.size > 2 * 1024 * 1024) { Hub.toast('A imagem tem mais de 2 MB.', 'erro'); return; }
        const dados = new FormData();
        dados.append('arquivo', f);
        const r = await api.post('/admin/marca/logo', dados);
        if (!r.ok) { Hub.modal(Hub.problema(r, 'Logo não enviada')); return; }
        arq.value = '';
        Hub.toast('Logo atualizada.');
        mostrar();
      };

      el.querySelector('[data-remover]').onclick = async () => {
        if (!confirm('Remover a logo do escritório? O hub volta a usar o ícone padrão.')) return;
        const r = await api.del('/admin/marca/logo');
        if (!r.ok) { Hub.modal(Hub.problema(r, 'Não foi possível remover a logo')); return; }
        Hub.toast('Logo removida.');
        mostrar();
      };

      mostrar();
    },
  });
})();
