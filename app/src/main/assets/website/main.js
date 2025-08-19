const $ = s => document.querySelector(s), tbody = $("#tbl tbody"), grid = $("#grid");

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

// ========== 预览支持 ==========
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
  document.body.style.overflow = 'hidden';   // 防止背景滚动
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
  document.body.style.overflow = '';         // 恢复背景滚动
}
document.addEventListener('keydown', (e)=>{ if (e.key === 'Escape') closePreview(); });

// ========== 加载 & 过滤 & 渲染 ==========
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

function parentPath(path){
  return path.replace(/\/+$/,'').split('/').slice(0,-1).join('/') || '/';
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
        ? `<button class="btn" onclick='openPreview(${JSON.stringify({name: it.name, path: it.path})})'>${isVideoExt(it.name)?'预览/播放':'预览'}</button>`
        : '';
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td class="col-check"><input type="checkbox" ${checked} onchange="toggleSelect('${it.path}', this.checked)" aria-label="选择 ${it.name}"></td>
        <td>📄 <span title="${it.name}">${it.name}</span></td>
        <td class="time">${it.lastModified || ''}</td>
        <td class="size">${it.size ?? (it.length ?? '-')}</td>
        <td class="download">${actionsPreview} <a class="btn" href="/dl?path=${encodeURIComponent(it.path)}" target="_blank" rel="noopener">下载</a></td>
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
      ? `<img class="thumb" loading="lazy" src="/thumb?path=${encodeURIComponent(it.path)}&w=320&h=200" alt="${it.name}">`
      : (!isUp && isVideoExt(it.name) && !it.isDir)
      ? `<img class="thumb" loading="lazy" src="/thumb?path=${encodeURIComponent(it.path)}&w=320&h=200&t=1000" alt="${it.name}">`
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
          ? `${previewAction} <a class="btn" href="/dl?path=${encodeURIComponent(it.path)}" target="_blank" rel="noopener">下载</a>
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

    // 缩略图点击预览
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

// 选择 / 批量操作
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
  if(selection.size === 0) return;
  if(!confirm('将为每个文件打开一个下载标签页（浏览器可能拦截弹窗）。继续？')) return;
  for(const p of selection){
    const a = document.createElement('a');
    a.href = `/dl?path=${encodeURIComponent(p)}`;
    a.target = '_blank';
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }
}

// 单项操作 & 目录操作
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
async function upload(){
  const path = document.getElementById('p').value;
  const files = document.getElementById('file').files;
  if(!files || files.length===0) { alert('请选择文件'); return; }
  const form = new FormData();
  for(const f of files) form.append('file', f, encodeURIComponent(f.name));
  const res = await fetch(`/upload?path=${encodeURIComponent(path)}`, {method:'POST', headers:{'X-Token':token}, body:form});
  alert(await res.text()); load();
}
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
