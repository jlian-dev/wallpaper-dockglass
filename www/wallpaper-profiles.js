/* Dock Líquida — perfis independentes por wallpaper
 * Carregar DEPOIS de www/script.js. Não substitui o editor nem o banco de imagens.
 * Salva os ajustes da Dock e a saída por ID do wallpaper no armazenamento local.
 */
(() => {
  if (typeof selecionar !== 'function' || typeof guardarEnquadramento !== 'function' ||
      typeof state !== 'object' || typeof FR !== 'object' || typeof OUT !== 'object') {
    console.error('Perfis por wallpaper: estrutura do Dock Líquida não encontrada.');
    return;
  }

  const cloneState = () => ({ ...state });
  const salvarPerfil = (id = curId) => {
    if (!id) return;
    FR[id] = {
      ...(FR[id] || {}),
      zoom: state.zoom,
      panX: state.panX,
      panY: state.panY,
      dock: cloneState(),
      saida: {
        w: OUT.w,
        h: OUT.h,
        modo: document.getElementById('outMode')?.value || 'auto'
      }
    };
    try { localStorage.setItem('dockFrames', JSON.stringify(FR)); }
    catch (e) { console.warn('Não foi possível guardar o perfil da Dock.', e); }
  };

  // Ao sair de um wallpaper, guarda tanto o enquadramento quanto a Dock completa.
  const guardarOriginal = guardarEnquadramento;
  guardarEnquadramento = function () {
    guardarOriginal();
    salvarPerfil(curId);
  };

  // Ao selecionar outro wallpaper, restaura o perfil correspondente.
  const selecionarOriginal = selecionar;
  selecionar = async function (id) {
    const perfilExistia = !!(FR[id] && FR[id].dock);
    const ok = await selecionarOriginal(id);
    if (!ok) return false;

    const perfil = FR[id];
    if (perfil && perfil.dock) {
      Object.assign(state, perfil.dock);
      if (perfil.saida) {
        OUT.w = Math.max(64, Math.min(8192, Number(perfil.saida.w) || OUT.w));
        OUT.h = Math.max(64, Math.min(8192, Number(perfil.saida.h) || OUT.h));
        const modo = document.getElementById('outMode');
        const ow = document.getElementById('outW');
        const oh = document.getElementById('outH');
        if (modo) modo.value = ['auto', 'device', 'custom'].includes(perfil.saida.modo) ? perfil.saida.modo : 'auto';
        if (ow) ow.value = OUT.w;
        if (oh) oh.value = OUT.h;
      }
      syncInputs();
      schedule();
    } else {
      // Primeira seleção: cria um perfil próprio para esta imagem.
      salvarPerfil(id);
    }
    return true;
  };

  // Também grava alterações feitas pelos controles, sem gravar a cada pixel.
  const saveSoonOriginal = typeof saveSoon === 'function' ? saveSoon : null;
  let perfilTimer;
  if (saveSoonOriginal) {
    saveSoon = function (...args) {
      const result = saveSoonOriginal.apply(this, args);
      clearTimeout(perfilTimer);
      perfilTimer = setTimeout(() => salvarPerfil(curId), 750);
      return result;
    };
  }

  // Garante que o perfil atual seja salvo quando o app vai para segundo plano/fecha.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') salvarPerfil(curId);
  });
  window.addEventListener('pagehide', () => salvarPerfil(curId));
})();
