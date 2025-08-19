
// === Guards: ensure helper functions exist even if this file is partially merged ===
(function(global){
  if (!global._fmtBytes) global._fmtBytes = function(n){
    if (typeof n !== 'number' || !isFinite(n) || n < 0) return '-';
    var units = ['B','KB','MB','GB','TB'];
    var u = 0, x = n;
    while (x >= 1024 && u < units.length-1){ x /= 1024; u++; }
    return (x >= 100 || u === 0 ? Math.round(x) : (x >= 10 ? x.toFixed(1) : x.toFixed(2))) + ' ' + units[u];
  };
  if (!global._ensurePanel) global._ensurePanel = function(){
    var panel = document.getElementById('download-progress-panel');
    if (panel) return panel;
    panel = document.createElement('div');
    panel.id = 'download-progress-panel';
    panel.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;min-width:260px;max-width:420px;background:#111;color:#fff;border-radius:12px;box-shadow:0 8px 24px rgba(0,0,0,.35);padding:14px 16px;font:14px/1.4 system-ui,Segoe UI,Roboto';
    panel.innerHTML = '' +
      '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">' +
      '  <strong>批量下载</strong>' +
      '  <button id="bulk-cancel-btn" class="btn" style="background:#333;color:#fff;border:none;border-radius:8px;padding:6px 10px;cursor:pointer">取消</button>' +
      '</div>' +
      '<div id="bulk-file" style="word-break:break-all;margin-bottom:6px;opacity:.9"></div>' +
      '<div id="bulk-meta" style="font-size:12px;opacity:.8;margin-bottom:8px"></div>' +
      '<div style="background:#333;border-radius:8px;height:8px;overflow:hidden;">' +
      '  <div id="bulk-bar" style="height:8px;width:0%;background:#4ade80;"></div>' +
      '</div>';
    document.body.appendChild(panel);
    var btn = document.getElementById('bulk-cancel-btn');
    if (btn) btn.onclick = function(){ if (global._bulkCtx){ global._bulkCtx.canceled = true; } };
    return panel;
  };
  if (!global._updatePanel) global._updatePanel = function(name, fileIndex, total, doneBytes, totalBytes){
    var panel = global._ensurePanel();
    panel.querySelector('#bulk-file').textContent = name || '';
    var percent = totalBytes > 0 ? Math.min(100, Math.round(doneBytes/totalBytes*100)) : Math.round(fileIndex/Math.max(1,total)*100);
    panel.querySelector('#bulk-meta').textContent = '进度：' + fileIndex + '/' + total + ' | ' + global._fmtBytes(doneBytes) + ' / ' + global._fmtBytes(totalBytes) + ' (' + percent + '%)';
    panel.querySelector('#bulk-bar').style.width = percent + '%';
  };
  if (!global._finishPanel) global._finishPanel = function(msg){
    if (msg === void 0) msg = '下载完成 ✅';
    var panel = global._ensurePanel();
    panel.querySelector('#bulk-file').textContent = '';
    panel.querySelector('#bulk-meta').textContent = msg;
    panel.querySelector('#bulk-bar').style.width = '100%';
    setTimeout(function(){ try{ panel.remove(); }catch(_){ } }, 1500);
  };
  if (!(' _bulkCtx ' in global)) global._bulkCtx = null;
})(window);

const $ = s => document.querySelector(s), tbody = $("#tbl tbody"), grid = $("#grid");

// === Download via hidden iframe (no <a>, no pop-up) ===
let _downloadFrame = null;
function ensureDownloadFrame(){
  if(!_downloadFrame){
    _downloadFrame = document.createElement('iframe');
    _downloadFrame.style.display = 'none';
    _downloadFrame.setAttribute('aria-hidden','true');
    document.body.appendChild(_downloadFrame);
  }
  return _downloadFrame;
}
function downloadFile(path){
  try{ _flashToast(`准备下载：${(String(path).split('/').pop()||path)}`); }catch(_){ }
  const frame = ensureDownloadFrame();
  frame.src = `/dl?path=${encodeURIComponent(path)}`;

// === Bulk download helpers: bytes format, progress panel, cancel ===

// --- tiny toast for single-file feedback ---
function _ensureToast(){
  let t = document.getElementById('download-progress-toast');
  if (t) return t;
  t = document.createElement('div');
  t.id = 'download-progress-toast';
  t.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;background:#111;color:#fff;border-radius:10px;padding:8px 12px;box-shadow:0 8px 24px rgba(0,0,0,.35);font:13px/1.4 system-ui,Segoe UI,Roboto;opacity:.95;';
  document.body.appendChild(t);
  return t;
}
function _flashToast(msg, ms=1200){
  const t = _ensureToast();
  t.textContent = msg;
  clearTimeout(_flashToast._tid);
  _flashToast._tid = setTimeout(()=>{ try{ t.remove(); }catch(_){ } }, ms);
}

function _fmtBytes(n){
  if (typeof n !== 'number' || !isFinite(n) || n < 0) return '-';
  const units = ['B','KB','MB','GB','TB'];
  let u = 0, x = n;
  while (x >= 1024 && u < units.length-1){ x /= 1024; u++; }
  return (x.toFixed(x>=100||u===0?0:(x>=10?1:2))) + ' ' + units[u];
}
let _bulkCtx = null;
function _ensurePanel(){
  let panel = document.getElementById('download-progress-panel');
  if (panel) return panel;
  panel = document.createElement('div');
  panel.id = 'download-progress-panel';
  panel.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483647;min-width:260px;max-width:420px;background:#111;color:#fff;border-radius:12px;box-shadow:0 8px 24px rgba(0,0,0,.35);padding:14px 16px;font:14px/1.4 system-ui,Segoe UI,Roboto';
  panel.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
      <strong>批量下载</strong>
      <button id="bulk-cancel-btn" class="btn" style="background:#333;color:#fff;border:none;border-radius:8px;padding:6px 10px;cursor:pointer">取消</button>
    </div>
    <div id="bulk-file" style="word-break:break-all;margin-bottom:6px;opacity:.9"></div>
    <div id="bulk-meta" style="font-size:12px;opacity:.8;margin-bottom:8px"></div>
    <div style="background:#333;border-radius:8px;height:8px;overflow:hidden;">
      <div id="bulk-bar" style="height:8px;width:0%;background:#4ade80;"></div>
    </div>
  `;
  document.body.appendChild(panel);
  document.getElementById('bulk-cancel-btn').onclick = () => {
    if (_bulkCtx){ _bulkCtx.canceled = true; }
  };
  return panel;
}
function _updatePanel(name, fileIndex, total, doneBytes, totalBytes){
  const panel = _ensurePanel();
  panel.querySelector('#bulk-file').textContent = name || '';
  const percent = totalBytes>0 ? Math.min(100, Math.round(doneBytes/totalBytes*100)) : Math.round(fileIndex/Math.max(1,total)*100);
  panel.querySelector('#bulk-meta').textContent = `进度：${fileIndex}/${total} | ${_fmtBytes(doneBytes)} / ${_fmtBytes(totalBytes)} (${percent}%)`;
  panel.querySelector('#bulk-bar').style.width = percent + '%';
}
function _finishPanel(msg='下载完成 ✅'){
  const panel = _ensurePanel();
  panel.querySelector('#bulk-file').textContent = '';
  panel.querySelector('#bulk-meta').textContent = msg;
  panel.querySelector('#bulk-bar').style.width = '100%';
  setTimeout(()=> panel.remove(), 1500);
}

  setTimeout(()=>{ try { _downloadFrame.removeAttribute('src'); } catch(_){} }, 1500);
}


let currentItemsRaw = [];
let currentItemsFiltered = [];
const selection = new Set();

const getPath = ()=>{
  const h = location.hash.slice(1);
  try { return h ? decodeURIComponent(h) : diskDir; } catch { return diskDir; }
};
const setHash = (p)=>{
  const nh = "#"+encodeURIComponent(p);
  if (location.hash !== nh) location.hash = nh;
};
const getView = ()=> localStorage.getItem("view") || "grid";
const setView = v => { localStorage.setItem("view", v); updateViewButtons(); applyView(v); };
const getTheme = ()=> localStorage.getItem("theme") || (window.matchMedia('(prefers-color-scheme: dark)').matches ? "dark" : "light");
const setTheme = t => { localStorage.setItem("theme", t); document.documentElement.dataset.theme = t; };

window.addEventListener("hashchange", () => {
  const p = getPath();
  if ($("#p").value !== p) { $("#p").value = p; load(true); }
});

document.addEventListener("DOMContentLoaded", () => {
  $("#p").value = getPath();
  setTheme(getTheme());
  updateViewButtons();
  applyView(getView());
  bindUploadUI();
  load(true);
});

function switchView(v){ setView(v); }
function updateViewButtons(){
  const v = getView();
  const bl = $("#btnList"), bg = $("#btnGrid");
  bl?.classList.toggle("active", v === "list");
  bg?.classList.toggle("active", v === "grid");
  bl?.setAttribute("aria-pressed", String(v === "list"));
  bg?.setAttribute("aria-pressed", String(v === "grid"));
}
function applyView(v){
  $("#tbl").classList.toggle("is-hidden", v !== "list");
  $("#grid").classList.toggle("is-hidden", v !== "grid");
}

function toggleTheme(){
  const next = (getTheme()==="dark")? "light":"dark";
  setTheme(next);
}

/* ===== 上传相关 UI（仅在文件拖拽时显示遮罩） ===== */
function bindUploadUI(){
  const file = $("#file");
  const hint = $("#fileHint");
  const dz = $("#dropzone");
  const inner = dz?.querySelector(".drop-inner");

  const showDZ = () => {
    dz?.classList.remove("hidden");
    dz?.setAttribute("aria-hidden","false");
  };
  const hideDZ = () => {
    dz?.classList.add("hidden");
    dz?.setAttribute("aria-hidden","true");
  };
  const isFileDrag = (e) => {
    const dt = e.dataTransfer;
    if (!dt) return false;
    if (dt.types && typeof dt.types.indexOf === "function") {
      if (dt.types.indexOf("Files") !== -1) return true;
    }
    if (dt.items && dt.items.length) {
      for (const it of dt.items) if (it.kind === "file") return true;
    }
    return false;
  };

  file?.addEventListener("change", ()=>{
    if (!file.files || file.files.length === 0) { 
      hint.textContent = "未选择文件"; 
      return; 
    }
    hint.textContent = `已选择 ${file.files.length} 个文件`;
  });

  // 仅在拖拽“文件”时显示遮罩
  window.addEventListener("dragenter", (e)=>{
    if (isFileDrag(e)) {
      e.preventDefault();
      showDZ();
    }
  });

  // 阻止默认仅针对文件拖拽；拖动按钮/链接等不显示遮罩
  window.addEventListener("dragover", (e)=>{
    if (isFileDrag(e)) {
      e.preventDefault();
    } else {
      hideDZ();
    }
  });

  // drop：仅文件时拦截并上传；否则保持默认行为
  window.addEventListener("drop", (e)=>{
    if (isFileDrag(e)) {
      e.preventDefault();
      const files = e.dataTransfer?.files;
      if (dz && !dz.classList.contains("hidden") && files && files.length && (e.target === dz || dz.contains(e.target))) {
        upload(files);
      }
      hideDZ();
    } else {
      hideDZ();
    }
  });

  window.addEventListener("dragend", ()=>{
    hideDZ();
  });

  // 拖出窗口也关闭遮罩
  document.addEventListener("dragleave", (e)=>{
    if (e.target === document.documentElement || e.target === document.body) {
      hideDZ();
    }
  });

  // 点击遮罩空白处关闭
  dz?.addEventListener("click", (e)=>{
    if (!inner || !inner.contains(e.target)) {
      hideDZ();
    }
  });

  // ESC 关闭
  document.addEventListener("keydown", (e)=>{
    if (e.key === "Escape") hideDZ();
  });

  // 支持从桌面拖拽文件到遮罩
  dz?.addEventListener("drop", (e)=>{
    if (isFileDrag(e)) {
      e.preventDefault();
      const files = e.dataTransfer?.files;
      hideDZ();
      if (files && files.length) upload(files);
    }
  });
}

function setUploadProgress(percent, metaText){
  const bar = $("#uploadProgress");
  const fill = bar?.querySelector(".fill");
  const per = $("#uploadPercent");
  const meta = $("#uploadMeta");
  if (!bar || !fill || !per || !meta) return;
  bar.classList.remove("is-hidden");
  const p = Math.max(0, Math.min(100, Math.floor(percent)));
  fill.style.width = p + "%";
  per.textContent = p + "%";
  if (metaText) meta.textContent = " · " + metaText;
  if (p >= 100){
    setTimeout(()=> bar.classList.add("is-hidden"), 800);
  }
}

// 改造 upload 支持原 fetch 方式和 XHR 进度条
async function upload(passedFiles){
  const path = document.getElementById('p').value;
  const input = document.getElementById('file');
  const files = passedFiles || input.files;
  if(!files || files.length===0) { alert('请选择文件'); return; }

  const form = new FormData();
  for(const f of files) form.append('file', f, encodeURIComponent(f.name));

  try{
    // 优先使用 XHR 以获得进度
    await new Promise((resolve, reject)=>{
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/upload?path=${encodeURIComponent(path)}`);
      xhr.setRequestHeader('X-Token', token);
      xhr.upload.onprogress = (e)=>{
        if (e.lengthComputable){
          const percent = (e.loaded / e.total) * 100;
          setUploadProgress(percent, `${files.length} 个文件`);
        }
      };
      xhr.onload = ()=> resolve();
      xhr.onerror = ()=> reject(new Error('网络错误'));
      xhr.onloadend = async ()=>{
        try{
          const text = xhr.responseText || '';
          alert(text || '上传完成');
        } finally {
          setUploadProgress(100, '完成');
          load();
        }
      };
      xhr.send(form);
    });
  } catch (e){
    // 退化到 fetch
    const res = await fetch(`/upload?path=${encodeURIComponent(path)}`, {method:'POST', headers:{'X-Token':token}, body:form});
    alert(await res.text());
    load();
  } finally {
    input.value = "";
    const hint = $("#fileHint");
    if (hint) hint.textContent = "未选择文件";
  }
}

/* ===== 预览支持 ===== */
function isImageExt(name){
  const ext = (name.split('.').pop() || '').toLowerCase();
  return ["jpg","jpeg","png","gif","webp","bmp","heic"].includes(ext);
}
function isVideoExt(name){
  const ext = (name.split('.').pop() || '').toLowerCase();
  return ["mp4","mkv","avi","mov","wmv","webm"].includes(ext);
}

function openPreview(item){
  const modal = document.getElementById('previewModal');
  const box = document.getElementById('previewContent');
  const title = document.getElementById('previewTitle');
  title.textContent = item.name || '预览';
  box.innerHTML = '';

  if (isImageExt(item.name)) {
    const img = document.createElement('img');
    img.src = `/open?path=${encodeURIComponent(item.path)}`;
    img.alt = item.name;
    box.appendChild(img);
  } else if (isVideoExt(item.name)) {
    const video = document.createElement('video');
    video.controls = true;
    video.playsInline = true;
    video.preload = 'metadata';
    video.src = `/open?path=${encodeURIComponent(item.path)}`;
    box.appendChild(video);
  } else {
    const a = document.createElement('a');
    a.className = 'btn';
    a.href = `/open?path=${encodeURIComponent(item.path)}`;
    a.target = '_blank'; a.rel = 'noopener';
    a.textContent = '在新标签页打开';
    box.appendChild(a);
  }
  modal.classList.add('open');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
}
function closePreview(){
  const modal = document.getElementById('previewModal');
  const box = document.getElementById('previewContent');
  if (!modal.classList.contains('open')) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
  const video = box.querySelector('video');
  if (video) { video.pause(); video.src=''; }
  box.innerHTML = '';
  document.body.style.overflow = '';
}
document.addEventListener('keydown', (e)=>{ if (e.key === 'Escape') closePreview(); });

/* ===== 加载 & 过滤 & 渲染 ===== */
async function load(skipHash = false){
  const path = $("#p").value;
  if (!skipHash) setHash(path);
  const res = await fetch(`/ls?path=${encodeURIComponent(path)}`, {headers:{'X-Token':token}});
  if(!res.ok){ alert(await res.text()); return; }
  const data = await res.json();
  selection.clear(); updateBulkbar();
  currentItemsRaw = data;
  applyFilters();
}

function applyFilters(){
  const path = $("#p").value;
  const q = $("#q").value.trim().toLowerCase();
  const sortVal = $("#sort").value;
  let list = [...currentItemsRaw];
  if(q){ list = list.filter(it => (it.name || '').toLowerCase().includes(q)); }
  const [key, order] = sortVal.split('.');
  const mul = order === 'asc' ? 1 : -1;
  list.sort((a,b)=>{
    if(key === 'name') return a.name.localeCompare(b.name) * mul;
    if(key === 'time'){
      const ta = Date.parse(a.lastModified || 0) || 0;
      const tb = Date.parse(b.lastModified || 0) || 0;
      return (ta - tb) * mul;
    }
    if(key === 'length'){
      const sa = Number(a.length || 0), sb = Number(b.length || 0);
      return (sa - sb) * mul;
    }
    return 0;
  });
  currentItemsFiltered = list;
  render(path, list);
}

/*function parentPath(path){
  return path.replace(/\/+/,'').replace(/\/+$/,'').split('/').slice(0,-1).join('/') || '/';
}*/

function parentPath(p){
  if (!p) return '/';
  // 1) 统一分隔符：Windows 反斜杠 -> 正斜杠
  p = String(p).replace(/[\\]+/g, '/');
  // 2) 合并重复的正斜杠
  p = p.replace(/\/+/g, '/');
  // 3) 去掉末尾斜杠（根目录除外）
  if (p !== '/') p = p.replace(/\/+$/g, '');
  // 4) 取上级
  const up = p.split('/').slice(0, -1).join('/');
  return up || '/';
}

function render(path, list){
  renderList(path, list);
  renderGrid(path, list);
  applyView(getView());
}

function renderList(path, data){
  tbody.innerHTML='';
  if(path!=='/'){
    const parent = parentPath(path);
    tbody.insertAdjacentHTML('beforeend', `<tr class="row go-up">
      <td class="col-check"></td>
      <td><a href="#" onclick="document.getElementById('p').value='${parent}';load();return false;" title="返回上级">..</a></td>
      <td class="time"></td>
      <td class="size"></td>
      <td class="download"></td>
      <td class="actions"></td>
    </tr>`);
  }
  for(const it of data){
    const checked = selection.has(it.path) ? 'checked' : '';
    if(it.isDir){
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td class="col-check"><input type="checkbox" ${checked} onchange="toggleSelect('${it.path}', this.checked)" aria-label="选择 ${it.name}"></td>
        <td>📁 <a href="#" onclick="document.getElementById('p').value='${it.path}';load();return false;" title="${it.name}">${it.name}</a></td>
        <td class="time">${it.lastModified || ''}</td>
        <td class="size">-</td>
        <td class="download">-</td>
        <td class="actions"><button class="btn-danger" onclick="del('${it.path}')">删除</button></td></tr>`);
    } else {
      const actionsPreview = (isImageExt(it.name) || isVideoExt(it.name))
        ? `<button class="btn" onclick='openPreview(${JSON.stringify({name: it.name, path: it.path})})'>${isVideoExt(it.name)?'播放':'预览'}</button>`
        : '';
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td class="col-check"><input type="checkbox" ${checked} onchange="toggleSelect('${it.path}', this.checked)" aria-label="选择 ${it.name}"></td>
        <td>📄 <span title="${it.name}">${it.name}</span></td>
        <td class="time">${it.lastModified || ''}</td>
        <td class="size">${it.size ?? (it.length ?? '-')}</td>
        <td class="download">${actionsPreview} <button class="btn" onclick='downloadFile(${JSON.stringify(it.path)})'>下载</button></td>
        <td class="actions"><button class="btn-danger" onclick="del('${it.path}')">删除</button></td></tr>`);
    }
  }
}

function iconFor(name, isDir){
  if(isDir) return "📁";
  if(isImageExt(name)) return "🖼️";
  if(isVideoExt(name)) return "🎞️";
  const ext = (name.split('.').pop() || '').toLowerCase();
  if(["mp3","wav","flac","aac","ogg","m4a"].includes(ext)) return "🎵";
  if(["zip","rar","7z","tar","gz"].includes(ext)) return "🗜️";
  if(["pdf"].includes(ext)) return "📕";
  if(["txt","md","log","json","xml","csv","kt","java","js","ts","html","css"].includes(ext)) return "📄";
  return "📦";
}

function renderGrid(path, data){
  grid.innerHTML = "";
  const items = [];
  if(path !== "/"){
    const parent = parentPath(path);
    items.push({ name: "..", path: parent, isDir: true, length: "-", lastModified: "" });
  }
  for(const it of data) items.push(it);

  for(const it of items){
    const isUp = it.name === "..";
    const checked = selection.has(it.path);
    const thumb = (!isUp && isImageExt(it.name) && !it.isDir)
      ? `<img class="thumb" loading="lazy" src="/thumb?path=${encodeURIComponent(it.path)}&w=300&h=300" alt="${it.name}">`
      : (!isUp && isVideoExt(it.name) && !it.isDir)
      ? `<img class="thumb" loading="lazy" src="/thumb?path=${encodeURIComponent(it.path)}&w=300&h=300&t=1000" alt="${it.name}">`
      : `<div class="thumb thumb-icon" aria-hidden="true">${isUp ? "⬆️" : iconFor(it.name, it.isDir)}</div>`;

    const subtitle = it.isDir ? "文件夹" : (it.size ?? (it.length ?? "-"));
    const time = it.lastModified || "";

    const canPreview = (!it.isDir && !isUp && (isImageExt(it.name) || isVideoExt(it.name)));
    const previewAction = canPreview
      ? `<button class="btn" onclick='openPreview(${JSON.stringify({name: it.name, path: it.path})})'>${isVideoExt(it.name)?'播放':'预览'}</button>`
      : '';

    const actions = it.isDir && !isUp
      ? `<button class="btn-danger" onclick="del('${it.path}')">删除</button>`
      : (!it.isDir && !isUp
          ? `${previewAction} <button class="btn" onclick='downloadFile(${JSON.stringify(it.path)})'>下载</button>
             <button class="btn-danger" onclick="del('${it.path}')">删除</button>`
          : "");

    const card = document.createElement("div");
    card.className = "file-card";
    card.innerHTML = `
      <label class="card-check"><input type="checkbox" ${checked ? "checked": ""} onchange="toggleSelect('${it.path}', this.checked)" aria-label="选择 ${it.name}"></label>
      <div class="thumb-wrap">${thumb}</div>
      <div class="file-meta">
        <div class="file-name" title="${it.name}">${it.name}</div>
        <div class="file-sub">${subtitle}</div>
        <div class="file-time">${time}</div>
      </div>
      <div class="file-actions">${actions}</div>
    `;

    card.addEventListener("click", (e)=>{
      if (e.target.closest(".file-actions") || e.target.closest(".card-check") || e.target.tagName === 'A' || e.target.tagName === 'BUTTON') return;
      if (isUp || it.isDir) { $("#p").value = it.path; load(); }
    });

    if (!it.isDir && !isUp && (isImageExt(it.name) || isVideoExt(it.name))) {
      setTimeout(()=>{
        const thumbEl = card.querySelector('.thumb-wrap');
        thumbEl.style.cursor = 'zoom-in';
        thumbEl.addEventListener('click', (e)=>{
          e.stopPropagation();
          openPreview({name: it.name, path: it.path});
        });
      }, 0);
    }

    grid.appendChild(card);
  }
}

/* 选择 / 批量操作 */
function toggleSelect(path, checked){
  if(checked) selection.add(path); else selection.delete(path);
  updateBulkbar();
}
function toggleSelectAll(checked){
  if(checked){
    currentItemsFiltered.forEach(it => selection.add(it.path));
  }else{
    currentItemsFiltered.forEach(it => selection.delete(it.path));
  }
  render(getPath(), currentItemsFiltered);
  updateBulkbar();
}
function updateBulkbar(){
  const count = selection.size;
  $("#selCount").textContent = `已选 ${count} 项`;
  $("#bulkbar").classList.toggle("is-hidden", count === 0);
  const allCount = currentItemsFiltered.length;
  const allChecked = (count>0 && count === allCount);
  $("#chkAll").checked = allChecked;
  $("#chkAllList").checked = allChecked;
}

async function bulkDelete(){
  if(selection.size === 0) return;
  if(!confirm(`确定删除选中的 ${selection.size} 项？`)) return;
  for(const p of Array.from(selection)){
    await fetch('/rm', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded','X-Token':token}, body:`path=${encodeURIComponent(p)}`});
  }
  load();
}
function bulkDownload(){
  if (selection.size === 0) return;

  const itemsByPath = new Map(currentItemsRaw.map(it => [it.path, it]));
  const files = Array.from(selection).filter(p => {
    const it = itemsByPath.get(p);
    return it && !it.isDir;
  });

  if (files.length === 0) { alert('请选择要下载的“文件”（文件夹请用打包ZIP）'); return; }

  const totalBytes = files.reduce((s,p)=>{
    const it = itemsByPath.get(p);
    const n = it && typeof it.length === 'number' ? it.length : 0;
    return s + (n>0?n:0);
  }, 0);

  {
  let msg = `将串行下载 ${files.length} 个文件`;
  if (totalBytes) msg += `（合计 ${_fmtBytes(totalBytes)}）`;
  msg += "。期间请勿关闭页面。继续？";
  if (!confirm(msg)) return;
}

  const frame = ensureDownloadFrame();
  let i = 0;
  let doneBytes = 0;

  _bulkCtx = { canceled: false };

  const next = () => {
    if (_bulkCtx.canceled){
      _finishPanel('已取消 ⛔');
      _bulkCtx = null;
      return;
    }
    if (i >= files.length){
      _finishPanel('下载完成 ✅');
      _bulkCtx = null;
      return;
    }
    const p = files[i++];
    const it = itemsByPath.get(p);
    const name = (p.split('/').pop()||p);
    _updatePanel(`正在下载：${name}${it && it.size ? '（'+it.size+'）' : ''}`, i, files.length, doneBytes, totalBytes);

    // 触发下载
    frame.src = `/dl?path=${encodeURIComponent(p)}`;

    // 估算完成：等一会儿再累计字节数并进入下一项（由于浏览器下载不可直接监听进度）
    const addAfterMs = 900; // 适当的节流，避免过快
    setTimeout(()=>{
      if (_bulkCtx && !_bulkCtx.canceled){
        if (it && typeof it.length === 'number' && it.length > 0) doneBytes += it.length;
        _updatePanel(`已完成：${name}`, i, files.length, doneBytes, totalBytes);
        setTimeout(next, 200); // 小间隔后继续下一个
      }
    }, addAfterMs);
  };

  _ensurePanel();
  next();
}

async function del(p){
  if(!confirm('确定删除？')) return;
  await fetch('/rm', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded','X-Token':token}, body:`path=${encodeURIComponent(p)}`});
  load();
}
async function mkdir(){
  const path = document.getElementById('p').value;
  const name = prompt('文件夹名'); if(!name) return;
  await fetch('/mkdir', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded','X-Token':token}, body:`path=${encodeURIComponent(path)}&name=${encodeURIComponent(name)}`});
  load();
}

// 打包 ZIP
async function bulkZip(){
  if(selection.size === 0) return;
  const paths = JSON.stringify(Array.from(selection));
  const res = await fetch('/zip', {
    method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded','X-Token':token},
    body: 'paths='+encodeURIComponent(paths)
  });
  if(!res.ok){ alert(await res.text()); return; }
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'pack.zip';
  document.body.appendChild(a);
  a.click();
  URL.revokeObjectURL(a.href);
  a.remove();
}
