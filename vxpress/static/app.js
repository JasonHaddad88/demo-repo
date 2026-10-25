(() => {
  "use strict";

  // The token comes from the URL `vxpress serve` printed. Keep it for this tab
  // only, and take it out of the address bar.
  const params = new URLSearchParams(location.search);
  const fromUrl = params.get("token");
  if (fromUrl) {
    sessionStorage.setItem("vx-token", fromUrl);
    params.delete("token");
    history.replaceState(null, "", location.pathname + (params.toString() ? "?" + params : ""));
  }
  const token = sessionStorage.getItem("vx-token") || "";

  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value === null || value === undefined ? "" : value)
    .replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  async function api(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: { "X-VXpress-Token": token, "Content-Type": "application/json", ...(options.headers || {}) },
    });
    let payload = {};
    try { payload = await response.json(); } catch (_) { /* no body */ }
    if (!response.ok) throw new Error(payload.error || `request failed (${response.status})`);
    return payload;
  }

  function flash(message, kind = "ok", detail = "") {
    const box = $("flash");
    box.className = kind;
    box.innerHTML = esc(message) + (detail ? `<pre>${esc(detail)}</pre>` : "");
    box.style.display = "block";
    clearTimeout(flash.timer);
    flash.timer = setTimeout(() => { box.style.display = "none"; }, 12000);
  }

  const kindOf = () => document.querySelector('input[name="kind"]:checked').value;

  function syncMode() {
    const isVideo = kindOf() === "video";
    $("opts-video").hidden = !isVideo;
    $("opts-mp3").hidden = isVideo;
  }
  document.querySelectorAll('input[name="kind"]').forEach((radio) =>
    radio.addEventListener("change", syncMode));

  // -- starting work ---------------------------------------------------
  function requestBody() {
    const kind = kindOf();
    const body = {
      kind,
      target: $("target").value.trim(),
      playlist: $("playlist").checked,
    };
    if (kind === "mp3") {
      const quality = $("bitrate").value;
      if (quality.startsWith("vbr:")) body.vbr = Number(quality.slice(4));
      else body.bitrate = Number(quality);
      body.start = $("start-at").value.trim();
      body.end = $("end-at").value.trim();
      body.thumbnail = $("thumbnail").checked;
      body.metadata = $("metadata").checked;
    } else {
      body.max_height = $("max-height").value ? Number($("max-height").value) : null;
      body.container = $("container").value;
    }
    return body;
  }

  async function start() {
    const body = requestBody();
    if (!body.target) { flash("Paste a link or a file path first.", "err"); return; }
    $("start").disabled = true;
    try {
      const job = await api("/api/jobs", { method: "POST", body: JSON.stringify(body) });
      flash(`Queued: ${job.title}`, "ok");
      $("preview").hidden = true;
      refreshJobs(true);
    } catch (error) {
      flash(error.message, "err");
    } finally {
      $("start").disabled = false;
    }
  }

  async function check() {
    const target = $("target").value.trim();
    if (!target) { flash("Paste a link or a file path first.", "err"); return; }
    const preview = $("preview");
    preview.hidden = false;
    preview.innerHTML = '<div class="empty">Looking it up…</div>';
    try {
      const payload = await api("/api/info?target=" + encodeURIComponent(target));
      const info = payload.info || {};
      const meta = payload.kind === "url"
        ? [info.uploader, info.duration_label, info.extractor].filter(Boolean).join(" · ")
        : [info.duration_label, info.audio_codec && `audio: ${info.audio_codec}`,
           info.video_codec && `video: ${info.video_codec}`].filter(Boolean).join(" · ");
      const warning = payload.kind === "file" && info.has_audio === false
        ? '<p class="meta">This file has no audio track, so there is nothing to convert.</p>' : "";
      const items = (info.entries || []).slice(0, 8)
        .map((entry) => `<li>${esc(entry.title)} <span class="meta">${esc(entry.duration_label)}</span></li>`)
        .join("");
      preview.innerHTML =
        `<h3>${esc(info.title || "unknown")}</h3><p class="meta">${esc(meta)}</p>${warning}` +
        (items ? `<ul>${items}</ul>` : "");
    } catch (error) {
      preview.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
    }
  }

  $("start").addEventListener("click", start);
  $("check").addEventListener("click", check);
  $("target").addEventListener("keydown", (event) => { if (event.key === "Enter") start(); });

  // -- drag and drop ---------------------------------------------------
  // A browser never reveals a dropped file's real path, so the file is sent to
  // the local server, which writes it to a temp folder and converts from there.
  function upload(file) {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      request.open("POST", "/api/upload");
      request.setRequestHeader("X-VXpress-Token", token);
      request.setRequestHeader("X-Filename", encodeURIComponent(file.name));
      request.setRequestHeader("Content-Type", "application/octet-stream");
      request.upload.onprogress = (event) => {
        if (!event.lengthComputable) return;
        const percent = (event.loaded / event.total) * 100;
        $("upload-fill").style.width = percent.toFixed(1) + "%";
        $("upload-label").textContent = `sending ${file.name} — ${percent.toFixed(0)}%`;
      };
      request.onload = () => {
        try {
          const payload = JSON.parse(request.responseText || "{}");
          if (request.status >= 400) reject(new Error(payload.error || "upload failed"));
          else resolve(payload);
        } catch (error) { reject(error); }
      };
      request.onerror = () => reject(new Error("upload failed"));
      request.send(file);
    });
  }

  let dragDepth = 0;
  window.addEventListener("dragenter", (event) => {
    if (!event.dataTransfer || ![...event.dataTransfer.types].includes("Files")) return;
    event.preventDefault();
    dragDepth += 1;
    document.body.classList.add("dragging");
  });
  window.addEventListener("dragover", (event) => {
    if (event.dataTransfer && [...event.dataTransfer.types].includes("Files")) event.preventDefault();
  });
  window.addEventListener("dragleave", () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) document.body.classList.remove("dragging");
  });
  window.addEventListener("drop", async (event) => {
    if (!event.dataTransfer || !event.dataTransfer.files.length) return;
    event.preventDefault();
    dragDepth = 0;
    document.body.classList.remove("dragging");
    const file = event.dataTransfer.files[0];
    $("upload").hidden = false;
    $("upload-fill").style.width = "0%";
    try {
      const saved = await upload(file);
      $("target").value = saved.path;
      document.querySelector('input[name="kind"][value="mp3"]').checked = true;
      syncMode();
      $("upload-label").textContent = `${saved.name} is ready — press Start`;
      flash(`${saved.name} uploaded to this computer's temp folder.`, "ok", saved.path);
    } catch (error) {
      $("upload").hidden = true;
      flash(error.message, "err");
    }
  });

  // -- the queue ---------------------------------------------------------
  const STATUS_PILL = { queued: "", running: "run", done: "done", failed: "failed", cancelled: "cancelled" };

  function jobCard(job) {
    const pill = STATUS_PILL[job.status] || "";
    const percent = job.status === "done" ? 100 : job.percent;
    const detail = [job.stage, job.options && job.kind === "mp3" ? job.options.quality : null,
                    job.seconds ? `${job.seconds}s` : null].filter(Boolean).join(" · ");
    const actions = [];
    if (job.status === "queued" || job.status === "running") {
      actions.push(`<button class="small" data-cancel="${esc(job.id)}">Cancel</button>`);
    }
    if (job.downloadable) {
      actions.push(
        `<a class="small" href="/api/download/${esc(job.id)}?token=${encodeURIComponent(token)}" download>` +
        `<button class="small primary">Save file…</button></a>`);
    }
    return `<article class="job ${esc(job.status)}">
      <header>
        <span class="name">${esc(job.title)}</span>
        <span class="pill ${pill}">${esc(job.status)}</span>
      </header>
      <div class="sub">${esc(job.source.value)}</div>
      ${job.status === "running" || job.status === "queued"
        ? `<div class="bar"><div class="fill" data-width="${percent}"></div></div>` : ""}
      <div class="sub">${esc(detail)}${job.message && job.status !== "done" ? " — " + esc(job.message) : ""}</div>
      ${job.output_path ? `<div class="sub">${esc(job.output_path)}</div>` : ""}
      <div class="actions">${actions.join(" ")}</div>
    </article>`;
  }

  let pollTimer = null;
  async function refreshJobs(immediate = false) {
    try {
      const payload = await api("/api/jobs");
      const jobs = payload.jobs || [];
      $("view-queue").innerHTML = jobs.length
        ? jobs.map(jobCard).join("")
        : '<div class="empty">Nothing queued yet.</div>';
      // Set bar widths through the CSSOM; a style="" attribute would be
      // refused by this page's Content-Security-Policy.
      $("view-queue").querySelectorAll(".fill[data-width]").forEach((fill) => {
        fill.style.width = `${fill.dataset.width}%`;
      });
      const busy = jobs.some((job) => job.status === "running" || job.status === "queued");
      clearTimeout(pollTimer);
      pollTimer = setTimeout(refreshJobs, busy ? 900 : 5000);
    } catch (error) {
      $("view-queue").innerHTML = `<div class="empty">${esc(error.message)}</div>`;
      clearTimeout(pollTimer);
      pollTimer = setTimeout(refreshJobs, 5000);
    }
    if (immediate) { /* caller wanted the list now; nothing else to do */ }
  }

  document.addEventListener("click", async (event) => {
    const cancel = event.target.closest("button[data-cancel]");
    if (!cancel) return;
    cancel.disabled = true;
    try {
      await api(`/api/jobs/${encodeURIComponent(cancel.dataset.cancel)}/cancel`, { method: "POST" });
      refreshJobs(true);
    } catch (error) {
      flash(error.message, "err");
    }
  });

  $("clear").addEventListener("click", async () => {
    try {
      const payload = await api("/api/jobs/clear", { method: "POST" });
      flash(`Cleared ${payload.removed} finished job(s).`, "ok");
      refreshJobs(true);
    } catch (error) { flash(error.message, "err"); }
  });

  // -- history -----------------------------------------------------------
  async function loadHistory() {
    const view = $("view-history");
    view.innerHTML = '<div class="empty">Loading…</div>';
    try {
      const payload = await api("/api/history?limit=100");
      const entries = payload.entries || [];
      view.innerHTML = entries.length
        ? `<div class="card table-wrap"><table><thead><tr>
             <th>When</th><th>Kind</th><th>Status</th><th>Quality</th><th>Result</th>
           </tr></thead><tbody>${entries.map((entry) => `<tr>
             <td class="mono">${esc(entry.ts)}</td>
             <td>${esc(entry.kind)}</td>
             <td>${esc(entry.status)}</td>
             <td>${esc(entry.quality || "")}</td>
             <td class="mono">${esc(entry.output || entry.message || entry.source || "")}</td>
           </tr>`).join("")}</tbody></table></div>`
        : '<div class="empty">Nothing finished yet.</div>';
    } catch (error) {
      view.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
    }
  }

  const views = { queue: () => refreshJobs(true), history: loadHistory };
  Object.keys(views).forEach((name) => {
    $("tab-" + name).addEventListener("click", () => {
      Object.keys(views).forEach((other) => {
        $("tab-" + other).setAttribute("aria-selected", String(other === name));
        $("view-" + other).hidden = other !== name;
      });
      views[name]();
    });
  });

  // -- boot ---------------------------------------------------------------
  (async () => {
    if (!token) {
      $("tools").innerHTML = '<span class="bad">no access token — reopen the URL that `vxpress serve` printed</span>';
      return;
    }
    try {
      const env = await api("/api/env");
      const tools = env.tools || {};
      const chip = (name) => {
        const tool = tools[name] || {};
        return tool.found
          ? `${name} <b>${esc(tool.version || "ok")}</b>`
          : `<span class="bad">${name} missing</span>`;
      };
      $("tools").innerHTML = `${chip("yt-dlp")} · ${chip("ffmpeg")} · ${esc(env.audio_dir)}`;

      const select = $("bitrate");
      (env.bitrates || [192]).forEach((rate) => {
        const option = document.createElement("option");
        option.value = String(rate);
        option.textContent = `${rate} kbps`;
        if (rate === env.default_bitrate) option.selected = true;
        select.appendChild(option);
      });
      [["vbr:0", "VBR — best"], ["vbr:2", "VBR — high"], ["vbr:5", "VBR — small"]].forEach(([value, label]) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        select.appendChild(option);
      });

      if (!tools["yt-dlp"] || !tools["yt-dlp"].found) {
        flash("yt-dlp is not installed, so links cannot be fetched.", "err",
              (tools["yt-dlp"] || {}).hint || "");
      } else if (!tools.ffmpeg || !tools.ffmpeg.found) {
        flash("ffmpeg is not installed, so mp3 output is unavailable.", "err",
              (tools.ffmpeg || {}).hint || "");
      }
    } catch (error) {
      $("tools").innerHTML = `<span class="bad">${esc(error.message)}</span>`;
    }
    syncMode();
    refreshJobs(true);
  })();
})();
