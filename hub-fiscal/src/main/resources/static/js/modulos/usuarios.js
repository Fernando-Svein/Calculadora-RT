/* Sistema: administração de usuários do hub (somente administradores). */
'use strict';

(() => {
  const { esc, api, comp } = Hub;

  const dataHora = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : 'Nunca acessou');

  function mostrarSenha(titulo, login, senha) {
    const c = Hub.modal(`<h2>${esc(titulo)}</h2>
      <p>Repasse ao usuário <strong>${esc(login)}</strong> a senha provisória abaixo. Ela aparece <strong>só agora</strong>
        e precisará ser trocada no primeiro acesso.</p>
      <div class="senha-provisoria">${esc(senha)}</div>
      <div class="acoes"><button class="btn" data-copiar>Copiar senha</button><button class="btn btn-sec" data-ok>Concluir</button></div>`);
    c.querySelector('[data-copiar]').onclick = async () => {
      try { await navigator.clipboard.writeText(senha); Hub.toast('Senha copiada.'); } catch (e) { Hub.toast('Selecione e copie a senha manualmente.', 'erro'); }
    };
    c.querySelector('[data-ok]').onclick = Hub.fecharModal;
  }

  /** Catálogo das ferramentas que podem ser liberadas (vem do servidor, que também aplica o bloqueio). */
  let catalogo = null;
  async function ferramentas() {
    if (!catalogo) {
      const r = await api.get('/admin/usuarios/ferramentas');
      catalogo = r.ok ? r.data : [];
    }
    return catalogo;
  }

  /** Seção de caixas de seleção por grupo, com "marcar o grupo" e contador. */
  function secaoPermissoes(lista, marcadas) {
    const grupos = [...new Set(lista.map((f) => f.grupo))];
    return `<fieldset class="perm" data-perm>
      <legend>Ferramentas liberadas</legend>
      <div class="perm-barra"><span class="sutil" data-cont></span><span class="espaco"></span>
        <button type="button" class="btn-link" data-todas>Marcar todas</button><button type="button" class="btn-link" data-nenhuma>Desmarcar todas</button></div>
      <div class="perm-grupos">${grupos.map((g) => `<div class="perm-grupo">
          <label class="perm-grupo-cab"><input type="checkbox" data-grupo="${esc(g)}"><span class="tile tile-${Hub.corGrupo(g)}">${Hub.iconeGrupo(g)}</span>
            <strong>${esc(g.replace(/^Calculadoras – /, 'Calculadoras · '))}</strong></label>
          ${lista.filter((f) => f.grupo === g).map((f) => `<label class="perm-item"><input type="checkbox" name="perm" value="${esc(f.id)}"
            data-g="${esc(g)}" ${marcadas.has(f.id) ? 'checked' : ''}>${Hub.iconeRota(f.id)}<span>${esc(f.titulo)}</span></label>`).join('')}
        </div>`).join('')}</div>
      <p class="sutil u-m-8-0-0 u-fs-12">Início, Situação do sistema e Guia rápido ficam sempre disponíveis.</p>
    </fieldset>
    <div class="info-caixa u-m-0" data-perm-admin>Administradores acessam todas as ferramentas e gerenciam usuários.</div>`;
  }

  function ligarPermissoes(f) {
    const caixas = [...f.querySelectorAll('[name=perm]')];
    const atualizar = () => {
      f.querySelectorAll('[data-grupo]').forEach((g) => {
        const doGrupo = caixas.filter((c) => c.dataset.g === g.dataset.grupo);
        const n = doGrupo.filter((c) => c.checked).length;
        g.checked = n === doGrupo.length;
        g.indeterminate = n > 0 && n < doGrupo.length;
      });
      const n = caixas.filter((c) => c.checked).length;
      f.querySelector('[data-cont]').textContent = n === caixas.length ? `Todas as ${n} ferramentas` : `${n} de ${caixas.length} ferramentas`;
    };
    caixas.forEach((c) => { c.onchange = atualizar; });
    f.querySelectorAll('[data-grupo]').forEach((g) => {
      g.onchange = () => { caixas.filter((c) => c.dataset.g === g.dataset.grupo).forEach((c) => { c.checked = g.checked; }); atualizar(); };
    });
    f.querySelector('[data-todas]').onclick = () => { caixas.forEach((c) => { c.checked = true; }); atualizar(); };
    f.querySelector('[data-nenhuma]').onclick = () => { caixas.forEach((c) => { c.checked = false; }); atualizar(); };
    const porPerfil = () => {
      const admin = f.perfil.value === 'ADMIN';
      f.querySelector('[data-perm]').hidden = admin;
      f.querySelector('[data-perm-admin]').hidden = !admin;
    };
    f.perfil.onchange = porPerfil;
    porPerfil();
    atualizar();
    return () => (f.perfil.value === 'ADMIN' ? null : caixas.filter((c) => c.checked).map((c) => c.value));
  }

  async function formulario(u, aoSalvar) {
    const novo = !u;
    const lista = await ferramentas();
    // novo usuário começa com tudo liberado; edição mostra o que ele tem hoje
    const marcadas = new Set(novo ? lista.map((f) => f.id) : u.permissoes);
    const c = Hub.modal(`<h2>${novo ? 'Novo usuário' : 'Editar usuário'}</h2>
      <form data-f class="form-usuario">
        <div class="grade g2">
          <div class="campo"><label>Nome *</label><input name="nome" value="${esc(u ? u.nome : '')}" required maxlength="100"></div>
          <div class="campo"><label>Usuário (login) *</label><input name="login" value="${esc(u ? u.login : '')}" ${novo ? 'required' : 'disabled'}
            autocapitalize="none" spellcheck="false" placeholder="ex.: maria.souza">
            ${novo ? '<span class="ajuda">Letras sem acento, números, ponto, hífen ou sublinhado.</span>' : ''}</div>
          <div class="campo"><label>Perfil</label><select name="perfil">
            <option value="USUARIO" ${u && u.perfil === 'USUARIO' ? 'selected' : ''}>Usuário – usa as ferramentas liberadas</option>
            <option value="ADMIN" ${u && u.perfil === 'ADMIN' ? 'selected' : ''}>Administrador – tudo, e gerencia usuários</option></select></div>
          ${novo ? '' : `<div class="campo"><label>Situação</label><label class="check u-h-40"><input type="checkbox" name="ativo" ${u.ativo ? 'checked' : ''}> Acesso liberado</label></div>`}
        </div>
        ${secaoPermissoes(lista, marcadas)}
        <div data-erro></div>
        <div class="acoes"><button class="btn" type="submit">${novo ? 'Criar usuário' : 'Salvar'}</button>
          <button class="btn btn-sec" type="button" data-cancelar>Cancelar</button></div>
      </form>`);
    const f = c.querySelector('[data-f]');
    const permissoes = ligarPermissoes(f);
    c.querySelector('[data-cancelar]').onclick = Hub.fecharModal;
    f.nome.focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const erro = f.querySelector('[data-erro]');
      const p = permissoes();
      if (p && !p.length && !confirm('Nenhuma ferramenta marcada: o usuário só verá Início, Situação do sistema e Guia rápido. Continuar?')) return;
      const r = novo
        ? await api.post('/admin/usuarios', { nome: f.nome.value, login: f.login.value, perfil: f.perfil.value, permissoes: p })
        : await api.put(`/admin/usuarios/${u.id}`, { nome: f.nome.value, perfil: f.perfil.value, ativo: f.ativo.checked, permissoes: p });
      if (!r.ok) { erro.innerHTML = Hub.problema(r, novo ? 'Usuário não criado' : 'Alteração não salva'); return; }
      if (novo) mostrarSenha('Usuário criado', r.data.login, r.data.senhaProvisoria);
      else { Hub.fecharModal(); Hub.toast('Usuário atualizado.'); }
      aoSalvar();
    };
  }

  Hub.registrar({
    rota: 'usuarios',
    titulo: 'Usuários',
    grupo: 'Sistema',
    perfil: 'ADMIN',
    ordem: 3,
    descricao: 'Cadastro de quem acessa o hub: criar usuários, perfis, bloquear acesso e redefinir senhas.',
    async render(el) {
      el.innerHTML = comp.cabecalho('Usuários', 'Quem pode acessar o hub. Novos usuários recebem uma senha provisória, trocada no primeiro acesso. ' +
        'Desativar um usuário encerra o acesso dele imediatamente.') +
        `<div class="card"><div class="card-cab"><h3>Usuários cadastrados</h3>
          <button class="btn" data-novo>${Hub.icone('mais')} Novo usuário</button></div><div data-lista></div></div>
        <div class="info-caixa">Segurança: senhas guardadas com criptografia (BCrypt); 5 tentativas erradas bloqueiam o usuário por 15 minutos;
          a sessão expira após 8 horas sem uso. O cadastro fica no arquivo <code>dados\\hub-usuarios.db</code> do servidor – inclua-o no backup.</div>`;
      const lista = el.querySelector('[data-lista]');

      async function carregar() {
        Hub.carregando(lista, 'Carregando usuários...');
        const [r, cat] = await Promise.all([api.get('/admin/usuarios'), ferramentas()]);
        if (!r.ok) { lista.innerHTML = Hub.problema(r); return; }
        const eu = Hub.usuario.id;
        const totalFerramentas = cat.length;
        const nomesFerramentas = (ids) => (ids.length ? cat.filter((f) => ids.includes(f.id)).map((f) => f.titulo).join('\n') : 'Nenhuma ferramenta');
        lista.innerHTML = `<div class="tabela-wrap u-maxh-none"><table class="tab tab-usuarios"><thead><tr>
            <th>Nome</th><th class="col-sec">Usuário</th><th>Perfil</th><th class="col-sec">Acessos</th><th>Situação</th><th class="col-sec">Último acesso</th><th></th></tr></thead><tbody>
          ${r.data.map((u) => `<tr data-id="${u.id}">
            <td><strong>${esc(u.nome)}</strong>${u.id === eu ? ' <span class="etq etq-azul">você</span>' : ''}</td>
            <td class="mono col-sec">${esc(u.login)}</td>
            <td>${u.perfil === 'ADMIN' ? '<span class="etq etq-azul">Administrador</span>' : 'Usuário'}</td>
            <td class="col-sec">${u.perfil === 'ADMIN' || u.acessoTotal ? '<span class="sutil">Todas as ferramentas</span>'
              : `<span class="acessos" title="${esc(nomesFerramentas(u.permissoes))}">${u.permissoes.length} de ${totalFerramentas} ferramentas</span>`}</td>
            <td>${!u.ativo ? '<span class="etq st-DIVERGENTE">Desativado</span>'
              : u.trocarSenha ? '<span class="etq st-REVISAR">Aguardando 1º acesso</span>' : '<span class="etq st-OK">Ativo</span>'}</td>
            <td class="col-sec">${esc(dataHora(u.ultimoAcesso))}</td>
            <td><div class="acoes-tab">
              <button class="btn-link" data-acao="editar">Editar</button>
              <button class="btn-link" data-acao="senha">Redefinir senha</button>
              ${u.id === eu ? '' : '<button class="btn-link perigo" data-acao="excluir">Excluir</button>'}
            </div></td></tr>`).join('')}
          </tbody></table></div>`;
        lista.querySelectorAll('[data-acao]').forEach((b) => {
          const u = r.data.find((x) => String(x.id) === b.closest('tr').dataset.id);
          b.onclick = async () => {
            if (b.dataset.acao === 'editar') formulario(u, carregar);
            if (b.dataset.acao === 'senha') {
              if (!confirm(`Gerar uma nova senha provisória para ${u.nome}? A senha atual deixa de funcionar.`)) return;
              const s = await api.post(`/admin/usuarios/${u.id}/redefinir-senha`);
              if (!s.ok) { Hub.modal(Hub.problema(s, 'Senha não redefinida')); return; }
              mostrarSenha('Senha redefinida', u.login, s.data.senhaProvisoria);
              carregar();
            }
            if (b.dataset.acao === 'excluir') {
              if (!confirm(`Excluir o usuário ${u.nome} (${u.login})? Prefira "Editar → desativar" se quiser apenas bloquear o acesso.`)) return;
              const x = await api.del(`/admin/usuarios/${u.id}`);
              if (!x.ok) { Hub.modal(Hub.problema(x, 'Usuário não excluído')); return; }
              Hub.toast('Usuário excluído.');
              carregar();
            }
          };
        });
      }
      el.querySelector('[data-novo]').onclick = () => formulario(null, carregar);
      carregar();
    },
  });
})();
