#!/usr/bin/env python3
# Generate the public support page (docs/index.html of the public repo) from profiles/support.json, so the page,
# the host app and the iPhone app always show the same table. GPL-3.0-or-later.
#   tools/gen-support-page.py [out.html]
import html
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
table = json.load(open(os.path.join(ROOT, "profiles", "support.json"), encoding="utf-8"))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "public", "docs", "index.html")

PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TosbiSim phones</title>
<meta name="description" content="Which Android phones work as a TosbiSim host: verified, expected and experimental models.">
<style>
:root { --bg:#f6f7f4; --card:#fff; --text:#1c1f1a; --muted:#5d6458; --line:#e3e6df; --accent:#5f8f2f;
  --verified:#2e7d32; --expected:#1565c0; --experimental:#b26a00; --not_yet:#5e35b1; --unsupported:#c62828; --unknown:#6b6b6b; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg:#121411; --card:#1b1e19; --text:#e9ece5;
  --muted:#a2a99c; --line:#2c3128; --accent:#8db653; --verified:#66bb6a; --expected:#64b5f6; --experimental:#ffb74d; --not_yet:#9575cd;
  --unsupported:#ef5350; --unknown:#9e9e9e; } }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
main { max-width: 860px; margin: 0 auto; padding: 28px 16px 48px; }
header { display:flex; align-items:center; gap:14px; flex-wrap:wrap; }
header h1 { margin:0; font-size:28px; }
.logo { font-size:34px; }
.lang { margin-left:auto; display:flex; gap:4px; }
.lang button { border:1px solid var(--line); background:var(--card); color:var(--text); border-radius:8px; padding:4px 10px; cursor:pointer; }
.lang button[aria-pressed="true"] { border-color:var(--accent); color:var(--accent); font-weight:600; }
p.lead { color:var(--muted); margin:10px 0 18px; }
input[type=search] { width:100%; padding:12px 14px; font-size:16px; border-radius:12px; border:1px solid var(--line);
  background:var(--card); color:var(--text); }
.legend { display:grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap:8px; margin:16px 0 8px; }
.legend div { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:10px 12px; font-size:14px; color:var(--muted); }
h2 { font-size:18px; margin:26px 0 10px; }
.phone { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:12px 14px; margin-bottom:8px; }
.phone .top { display:flex; align-items:baseline; gap:8px; flex-wrap:wrap; }
.phone .name { font-weight:600; }
.phone .meta { color:var(--muted); font-size:13px; }
.phone .note { font-size:14px; margin-top:4px; }
.badge { font-size:12px; font-weight:600; padding:2px 8px; border-radius:999px; margin-left:auto; white-space:nowrap; }
.rules li { margin-bottom:4px; }
.empty { color:var(--muted); }
footer { margin-top:32px; color:var(--muted); font-size:13px; }
a { color:var(--accent); }
</style>
</head>
<body>
<main>
  <header>
    <span class="logo" aria-hidden="true">🐢</span>
    <h1 data-t="title"></h1>
    <div class="lang" role="group" aria-label="Language">
      <button data-lang="en">English</button><button data-lang="tr">Türkçe</button>
    </div>
  </header>
  <p class="lead" data-t="lead"></p>
  <input type="search" id="q" autocomplete="off">
  <div class="legend" id="legend"></div>
  <div id="list"></div>
  <h2 data-t="rulesTitle"></h2>
  <ul class="rules" id="rules"></ul>
  <footer><span data-t="footer"></span> · <span id="updated"></span> ·
    <a href="https://github.com/Mert-Eren-Karabulut/tosbisim">GitHub</a></footer>
</main>
<script>
const DATA = __DATA__;
const T = {
  en: { title: "Which phones work with TosbiSim?",
    lead: "TosbiSim turns a spare rooted Android phone into a SIM box for your iPhone. Every phone runs a short call test before call audio turns on; texts, call control and the hotspot work in Basic mode on most rootable Android 11+ phones.",
    search: "Search a model, e.g. A52 or Redmi Note 10",
    tiers: { verified: "Verified", expected: "Expected to work", experimental: "Experimental", not_yet: "Not yet", unsupported: "Not supported", unknown: "Not researched yet" },
    legend: { verified: "Measured by us on a live call.", expected: "Its own firmware matches a verified phone's audio family.",
              experimental: "Our audio family, not confirmed yet.", not_yet: "Audio family we can't drive yet: Basic mode only.",
              unsupported: "Can't be used as a host." },
    familiesTitle: "Audio families", famYes: "supported", famNo: "not yet",
    corpus: n => `Search for your phone: over 45,000 models are listed with their state, ${n} of them checked against their firmware.`,
    rulesTitle: "Never supported", none: "Not in the list yet: Basic mode should work; Qualcomm and MediaTek phones can also try the call test.",
    footer: "Same table as the TosbiSim apps", updated: "updated" },
  tr: { title: "TosbiSim hangi telefonlarla çalışır?",
    lead: "TosbiSim, root'lu yedek bir Android telefonu iPhone'unuz için bir SIM kutusuna dönüştürür. Her telefonda arama sesi kısa bir arama testinden sonra açılır; root'lanabilen çoğu Android 11+ telefonda mesajlar, arama yönetimi ve erişim noktası Temel modda çalışır.",
    search: "Model arayın, ör. A52 veya Redmi Note 10",
    tiers: { verified: "Doğrulandı", expected: "Çalışması bekleniyor", experimental: "Deneysel", not_yet: "Henüz değil", unsupported: "Desteklenmiyor", unknown: "Henüz incelenmedi" },
    legend: { verified: "Canlı aramada bizim tarafımızdan ölçüldü.", expected: "Kendi yazılımı doğrulanmış bir telefonun ses ailesiyle uyuşuyor.",
              experimental: "Bizim ses ailemiz, henüz doğrulanmadı.", not_yet: "Henüz yönetemediğimiz ses ailesi: yalnızca Temel mod.",
              unsupported: "Ana cihaz olarak kullanılamaz." },
    familiesTitle: "Ses aileleri", famYes: "destekleniyor", famNo: "henüz değil",
    corpus: n => `Telefonunuzu arayın: 45.000'den fazla model durumuyla listeleniyor, ${n} tanesi yazılımına göre incelendi.`,
    rulesTitle: "Hiçbir zaman desteklenmeyenler", none: "Henüz listede yok: Temel mod çalışmalı; Qualcomm ve MediaTek telefonlar arama testini de deneyebilir.",
    footer: "TosbiSim uygulamalarıyla aynı tablo", updated: "güncelleme" } };
const ORDER = ["verified", "expected", "experimental", "not_yet"];
let lang = (navigator.language || "en").toLowerCase().startsWith("tr") ? "tr" : "en";
const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
// The full phone index (~46k phones) lives on api.tosbisim.com; this page's own table answers instantly and offline.
const API = "https://api.tosbisim.com/api/v1/devices?q=";
let remote = null, remoteFor = "", timer = null;
function search() {
  const q = $("#q").value.trim();
  render();
  clearTimeout(timer);
  if (q.length < 2) { remote = null; return; }
  timer = setTimeout(async () => {
    try {
      const r = await fetch(API + encodeURIComponent(q));
      if (r.ok && $("#q").value.trim() === q) { remote = (await r.json()).results; remoteFor = q.toLowerCase(); render(); }
    } catch (e) { /* offline: keep the page's own matches */ }
  }, 300);
}
function phoneHtml(t, tier, name, meta, note) {
  return `<div class="phone"><div class="top"><span class="name">${esc(name)}</span>
    <span class="meta">${esc(meta)}</span>
    <span class="badge" style="color:var(--${tier});border:1px solid var(--${tier})">${t.tiers[tier] || tier}</span></div>
    ${note ? `<div class="note">${esc(note)}</div>` : ""}</div>`;
}
function render() {
  const t = T[lang];
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-t]").forEach(e => e.textContent = t[e.dataset.t]);
  document.querySelectorAll(".lang button").forEach(b => b.setAttribute("aria-pressed", b.dataset.lang === lang));
  $("#q").placeholder = t.search;
  $("#updated").textContent = t.updated + " " + DATA.updated;
  $("#legend").innerHTML = [...ORDER, "unsupported"].map(k =>
    `<div><b style="color:var(--${k})">${t.tiers[k]}</b><br>${t.legend[k]}</div>`).join("");
  const q = $("#q").value.trim().toLowerCase();
  // ~1400 phones come from firmware dumps (source "corpus"): shown when searched for, not listed up front
  const hit = p => q ? [p.brand, p.name, p.soc, ...p.models].join(" ").toLowerCase().includes(q) : p.source !== "corpus";
  let html = "";
  const fams = DATA.families || {};
  const famName = f => f && fams[f] ? (lang === "tr" ? (fams[f].name_tr || fams[f].name) : fams[f].name) : "";
  if (q && remote && remoteFor === q) {
    for (const tier of [...ORDER, "unsupported", "unknown"]) {
      const hits = remote.filter(h => h.tier === tier);
      if (!hits.length) continue;
      html += `<h2 style="color:var(--${tier})">${t.tiers[tier]}</h2>`;
      for (const h of hits) {
        const own = h.hand ? DATA.phones.find(p => p.source !== "corpus" && p.models.some(m => h.models.includes(m))) : null;
        const note = own ? (lang === "tr" ? (own.note_tr || own.note) : own.note) : (lang === "tr" ? (h.reason_tr || h.reason) : h.reason);
        const name = h.name.toLowerCase().startsWith(h.brand.toLowerCase()) ? h.name : `${h.brand} ${h.name}`;
        html += phoneHtml(t, tier, name, [h.models.slice(0, 4).join(", "), famName(h.family)].filter(Boolean).join(" · "), note);
      }
    }
    $("#list").innerHTML = html || `<p class="empty">${t.none}</p>`;
    $("#rules").innerHTML = DATA.rules.map(r => `<li>${esc(lang === "tr" ? (r.reason_tr || r.reason) : r.reason)}</li>`).join("");
    return;
  }
  for (const tier of ORDER) {
    const phones = DATA.phones.filter(p => p.tier === tier && hit(p));
    if (!phones.length) continue;
    html += `<h2 style="color:var(--${tier})">${t.tiers[tier]}</h2>`;
    for (const p of phones) {
      const note = lang === "tr" ? (p.note_tr || p.note) : p.note;
      html += `<div class="phone"><div class="top"><span class="name">${esc(p.brand)} ${esc(p.name)}</span>
        <span class="meta">${esc(p.models.join(", "))} · ${esc(p.soc)}</span>
        <span class="badge" style="color:var(--${tier});border:1px solid var(--${tier})">${t.tiers[tier]}</span></div>
        <div class="note">${esc(note)}</div></div>`;
    }
  }
  if (!q) html += `<p class="empty">${t.corpus(DATA.phones.filter(p => p.source === "corpus").length)}</p>`;
  html += `<h2>${t.familiesTitle}</h2>` + Object.values(fams).map(f => `<div class="phone"><div class="top"><span class="name">${esc(lang === "tr" ? (f.name_tr || f.name) : f.name)}</span>
    <span class="badge" style="color:var(--${f.router ? "verified" : "not_yet"});border:1px solid var(--${f.router ? "verified" : "not_yet"})">${f.router ? t.famYes : t.famNo}</span></div>
    <div class="note">${esc(lang === "tr" ? (f.about_tr || f.about) : f.about)}</div></div>`).join("");
  $("#list").innerHTML = html || `<p class="empty">${t.none}</p>`;
  $("#rules").innerHTML = DATA.rules.map(r => `<li>${esc(lang === "tr" ? (r.reason_tr || r.reason) : r.reason)}</li>`).join("");
}
document.querySelectorAll(".lang button").forEach(b => b.onclick = () => { lang = b.dataset.lang; render(); });
$("#q").oninput = search;
render();
</script>
</body>
</html>
"""

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    f.write(PAGE.replace("__DATA__", json.dumps(table, ensure_ascii=False)))
print("support page:", out)
