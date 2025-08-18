const $ = s => document.querySelector(s), tbody = $("#tbl tbody"), grid = $("#grid");

/** 路由 & 视图状态 **/
const getPath = ()=>{
    const h = location.hash.slice(1);
    try { return h ? decodeURIComponent(h) : diskDir; } catch { return diskDir; }
};
const setHash = (p)=>{
    const nh = "#"+encodeURIComponent(p);
    if (location.hash !== nh) location.hash = nh;
};
const getView = ()=> localStorage.getItem("view") || "list";
const setView = v => { localStorage.setItem("view", v); updateViewButtons(); applyView(v); };

window.addEventListener("hashchange", () => {
    const p = getPath();
    if ($("#p").value !== p) { $("#p").value = p; load(true); }
});

document.addEventListener("DOMContentLoaded", () => {
    $("#p").value = getPath();
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
    bl?.setAttribute("aria-pressed", v === "list");
    bg?.setAttribute("aria-pressed", v === "grid");
}
function applyView(v){
    const listEl = $("#tbl");
    const gridEl = $("#grid");
    listEl.classList.toggle("is-hidden", v !== "list");
    gridEl.classList.toggle("is-hidden", v !== "grid");
}

/** 目录加载 **/
async function load(skipHash = false){
  const path = $("#p").value;
  if (!skipHash) setHash(path);

  const res = await fetch(`/ls?path=${encodeURIComponent(path)}`, {headers:{'X-Token':token}});
  if(!res.ok){ alert(await res.text()); return; }
  const data = await res.json();
  renderList(path, data);
  renderGrid(path, data);
  applyView(getView());
}

function parentPath(path){
    return path.replace(/\/+$/,'').split('/').slice(0,-1).join('/') || '/';
}

function renderList(path, data){
  tbody.innerHTML='';
  if(path!=='/'){
    const parent = parentPath(path);
    tbody.insertAdjacentHTML('beforeend', `<tr class="row">
    <td><a href="#" onclick="document.getElementById('p').value='${parent}';load();return false;">..</a></td>
    <td class="time"></td>
    <td class="size"></td>
    <td class="download"></td>
    <td class="actions"></td>
    </tr>`);
  }
  for(const it of data){
    if(it.isDir){
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td>📁 <a href="#" onclick="document.getElementById('p').value='${it.path}';load();return false;">${it.name}</a></td>
        <td class="time">${it.lastModified}</td>
        <td class="size">-</td>
        <td class="download">-</td>
        <td class="actions"><button onclick="del('${it.path}')">删除</button></td></tr>`.replace(/it\.path/g, it.path).replace(/it\.name/g, it.name));
    } else {
      tbody.insertAdjacentHTML('beforeend', `<tr class="row">
        <td>📄 ${it.name}</td>
        <td class="time">${it.lastModified}</td>
        <td class="size">${it.size}</td>
        <td class="download"><a href="/dl?path=${encodeURIComponent(it.path)}" target="_blank">下载</a></td>
        <td class="actions"><button onclick="del('${it.path}')">删除</button></td></tr>`);
    }
  }
}

function iconFor(name, isDir){
  if(isDir) return "📁";
  const ext = (name.split('.').pop() || '').toLowerCase();
  if(["jpg","jpeg","png","gif","webp","bmp","heic"].includes(ext)) return "🖼️";
  if(["mp4","mkv","avi","mov","wmv","webm"].includes(ext)) return "🎞️";
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
    items.push({ name: "..", path: parent, isDir: true, size: "-", lastModified: "" });
  }
  for(const it of data) items.push(it);

  for(const it of items){
    const isUp = it.name === "..";
    const icon = isUp ? "⬆️" : iconFor(it.name, it.isDir);
    const subtitle = it.isDir ? "文件夹" : (it.size || "-");
    const time = it.lastModified || "";

    const actions = it.isDir && !isUp
      ? `<button class="danger" onclick="del('${it.path}')">删除</button>`
      : (!it.isDir && !isUp
          ? `<a class="btn" href="/dl?path=${encodeURIComponent(it.path)}" target="_blank">下载</a>
             <button class="danger" onclick="del('${it.path}')">删除</button>`
          : "");

    const card = document.createElement("div");
    card.className = "file-card";
    card.innerHTML = `
      <div class="file-icon" aria-hidden="true">${icon}</div>
      <div class="file-meta">
        <div class="file-name" title="${it.name}">${it.name}</div>
        <div class="file-sub">${subtitle}</div>
        <div class="file-time">${time}</div>
      </div>
      <div class="file-actions">${actions}</div>
    `;

    card.addEventListener("click", (e)=>{
        if (e.target.closest(".file-actions")) return;
        if (isUp || it.isDir) {
            $("#p").value = it.path;
            load();
        }
    });

    grid.appendChild(card);
  }
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

async function upload(){
  const path = document.getElementById('p').value;
  const files = document.getElementById('file').files;
  const form = new FormData();
  for(const f of files) form.append('file', f, encodeURIComponent(f.name));
  const res = await fetch(`/upload?path=${encodeURIComponent(path)}`, {method:'POST', headers:{'X-Token':token}, body:form});
  alert(await res.text()); load();
}
