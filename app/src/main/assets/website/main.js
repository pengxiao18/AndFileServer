const $ = s => document.querySelector(s), tbody = $("#tbl tbody"), grid = $("#grid");

// ---- 全局状态 ----
let currentItemsRaw = [];           // 后端原始数据（当前目录）
let currentItemsFiltered = [];      // 过滤/排序后的数据
const selection = new Set();        // 选中的 path 集合

// 路径 & 视图 & 主题
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

// 事件: 路由
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

// ---- 加载目录 ----
async function load(skipHash = false){
  const path = $("#p").value;
  if (!skipHash) setHash(path);

  const res = await fetch(`/ls?path=${encodeURIComponent(path)}`, {headers:{'X-Token':token}});
  if(!res.ok){ alert(await res.text()); return; }
  const data = await res.json();

  // 重置状态
  selection.clear();
  updateBulkbar();
  currentItemsRaw = data;
  applyFilters(); // 会触发 render
}

// ---- 过滤 & 排序 ----
function applyFilters(){
  const path = $("#p").value;
  const q = $("#q").value.trim().toLowerCase();
  const sortVal = $("#sort").value; // e.g. name.asc

  let list = [...currentItemsRaw];

  // 搜索：仅按名称匹配
  if(q){
    list = list.filter(it => (it.name || '').toLowerCase().includes(q));
  }

  // 排序
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

// ---- 渲染 ----
function render(path, list){
  renderList(path, list);
  renderGrid(path, list);
  applyView(getView());
}

function renderList(path, data){
  tbody.innerHTML='';
  // 上级目录
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
      const size = it.size ?? (it.length ?? '-');
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td class="col-check"><input type="checkbox" ${checked} onchange="toggleSelect('${it.path}', this.checked)" aria-label="选择 ${it.name}"></td>
        <td>📄 <span title="${it.name}">${it.name}</span></td>
        <td class="time">${it.lastModified || ''}</td>
        <td class="size">${size}</td>
        <td class="download"><a class="btn" href="/dl?path=${encodeURIComponent(it.path)}" target="_blank" rel="noopener">下载</a></td>
        <td class="actions"><button class="btn-danger" onclick="del('${it.path}')">删除</button></td></tr>`);
    }
  }
}

function isImageExt(name){
  const ext = (name.split('.').pop() || '').toLowerCase();
  return ["jpg","jpeg","png","gif","webp","bmp","heic"].includes(ext);
}
function isVideoExt(name){
  const ext = (name.split('.').pop() || '').toLowerCase();
  return ["mp4","mkv","avi","mov","wmv","webm"].includes(ext);
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

    const subtitle = it.isDir ? "文件夹" : ((it.size ?? it.length) ?? "-");
    const time = it.lastModified || "";

    const actions = it.isDir && !isUp
      ? `<button class="btn-danger" onclick="del('${it.path}')">删除</button>`
      : (!it.isDir && !isUp
          ? `<a class="btn" href="/dl?path=${encodeURIComponent(it.path)}" target="_blank" rel="noopener">下载</a>
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

    // 点击卡片空白处进入目录或无动作；避免与按钮/勾选冲突
    card.addEventListener("click", (e)=>{
      if (e.target.closest(".file-actions") || e.target.closest(".card-check") || e.target.tagName === 'A' || e.target.tagName === 'BUTTON') return;
      if (isUp || it.isDir) { $("#p").value = it.path; load(); }
    });

    grid.appendChild(card);
  }
}

// ---- 选择 / 批量操作 ----
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
  // 同步 UI
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

// ---- 单项操作 ----
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
  if(!res.ok){
    alert(await res.text()); return;
  }
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'pack.zip';
  document.body.appendChild(a);
  a.click();
  URL.revokeObjectURL(a.href);
  a.remove();
}
