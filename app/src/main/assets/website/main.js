
const $ = s => document.querySelector(s), tbody = $("#tbl tbody");

// 仅用 URL Hash 记住目录
const getPath = ()=>{
    const h = location.hash.slice(1);
    try { return h ? decodeURIComponent(h) : diskDir; } catch { return diskDir; }
};
const setHash = (p)=>{
    const nh = "#"+encodeURIComponent(p);
    if (location.hash !== nh) location.hash = nh;
};

// ✅ 刷新/前进/后退：根据 hash 恢复，并且首轮加载不要再改 hash
window.addEventListener("hashchange", () => {
    const p = getPath();
    if ($("#p").value !== p) { $("#p").value = p; load(true); } // true = 本次不写 hash
});

 // ✅ 初始化顺序：先用 hash 覆盖输入框，再做“首轮加载（不写 hash）”
document.addEventListener("DOMContentLoaded", () => {
    $("#p").value = getPath();  // 先恢复
    load(true);                 // 首次加载不覆盖 hash
});

async function load(skipHash = false){
  // const path = document.getElementById('p').value;
  const path = $("#p").value;
  if (!skipHash) setHash(path);      // 只有用户显式切目录时才更新 hash

  const res = await fetch(`/ls?path=${encodeURIComponent(path)}`, {headers:{'X-Token':token}});
  if(!res.ok){ alert(await res.text()); return; }
  const data = await res.json();
  const tb = document.querySelector('#tbl tbody'); tb.innerHTML='';
  if(path!=='/'){
    const parent = path.replace(/\/+$/,'').split('/').slice(0,-1).join('/') || '/';
    tb.insertAdjacentHTML('beforeend', `<tr class="row">
    <td><a href="#" onclick="document.getElementById('p').value='${parent}';load();return false;">..</a></td>
    <td class="size"></td>
    <td class="download"></td>
    <td class="actions"></td>
    </tr>`);
  }
  for(const it of data){
    if(it.isDir){
      tb.insertAdjacentHTML('beforeend', `<tr class="row">
        <td>📁 <a href="#" onclick="document.getElementById('p').value='${it.path}';load();return false;">${it.name}</a></td>
        <td class="size">-</td>
        <td class="download">-</td>  <!-- 无下载，占位 -->
        <td class="actions"><button onclick="del('${it.path}')">删除</button></td></tr>`.replace(/it\.path/g, it.path).replace(/it\.name/g, it.name));
    } else {
      tb.insertAdjacentHTML('beforeend', `<tr class="row">
        <td>📄 ${it.name}</td>
        <td class="size">${it.size}</td>
        <td class="download"><a href="/dl?path=${encodeURIComponent(it.path)}" target="_blank">下载</a></td>
        <td class="actions"><button onclick="del('${it.path}')">删除</button></td></tr>`);
    }
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

// load();