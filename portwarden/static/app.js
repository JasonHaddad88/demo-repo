(() => {
  "use strict";

  // The token arrives in the URL printed by `portwarden serve`. Keep it for
  // this tab only and scrub it from the address bar.
  const params = new URLSearchParams(location.search);
  const fromUrl = params.get("token");
  if (fromUrl) {
    sessionStorage.setItem("pw-token", fromUrl);
    params.delete("token");
    const clean = location.pathname + (params.toString() ? "?" + params : "");
    history.replaceState(null, "", clean);
  }
  const token = sessionStorage.getItem("pw-token") || "";

  const $ = (id) => document.getElementById(id);
  const text = (value) => (value === null || value === undefined ? "" : String(value));
  const esc = (value) => text(value).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const DESTRUCTIVE = new Set(["stop", "disable", "restart"]);

  async function api(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: { "X-PortWarden-Token": token, "Content-Type": "application/json", ...(options.headers || {}) },
    });
    let payload = {};
    try { payload = await response.json(); } catch (_) { /* empty body */ }
    if (!response.ok && payload.error) throw new Error(payload.error);
    return { response, payload };
  }

  function flash(message, kind = "ok", command = "") {
    const box = $("flash");
    box.className = kind;
    box.innerHTML = esc(message) + (command ? `<span class="cmd">${esc(command)}</span>` : "");
    box.style.display = "block";
    clearTimeout(flash.timer);
    flash.timer = setTimeout(() => { box.style.display = "none"; }, 9000);
  }

  function confirmAction(title, body) {
    return new Promise((resolve) => {
      const dialog = $("confirm");
      $("confirm-title").textContent = title;
      $("confirm-body").textContent = body;
      const close = (answer) => { dialog.close(); resolve(answer); };
      $("confirm-yes").onclick = () => close(true);
      $("confirm-no").onclick = () => close(false);
      dialog.showModal();
    });
  }

  const yesNo = (value) => value === true
    ? '<span class="pill on">yes</span>'
    : value === false ? '<span class="pill off">no</span>' : '<span class="pill">?</span>';

  function actionButtons(target, service) {
    const verbs = [
      ["start", "Start", service && service.running === true],
      ["stop", "Stop", service && service.running === false],
      ["restart", "Restart", false],
      ["enable", "Enable", service && service.enabled === true],
      ["disable", "Disable", service && service.enabled === false],
    ];
    return `<div class="row-actions">${verbs.map(([verb, label, off]) =>
      `<button class="action${DESTRUCTIVE.has(verb) ? " danger" : ""}" data-action="${verb}"
        data-target="${esc(JSON.stringify(target))}" ${off ? "disabled" : ""}>${label}</button>`
    ).join("")}</div>`;
  }

  async function runAction(verb, target, label) {
    if (DESTRUCTIVE.has(verb)) {
      const ok = await confirmAction(
        `${verb} ${label}?`,
        verb === "disable"
          ? `${label} will not start at the next boot. It keeps running until you stop it.`
          : `${label} will ${verb === "stop" ? "stop now" : "restart now"}; anything using its port drops.`
      );
      if (!ok) return;
    }
    const body = { action: verb, force: $("allow-protected").checked, ...target };
    try {
      const { payload } = await api("/api/control", { method: "POST", body: JSON.stringify(body) });
      const results = payload.results || [];
      const failed = results.filter((r) => !r.ok);
      const first = results[0] || {};
      if (failed.length) {
        flash(failed.map((r) => `${r.action} ${r.service}: ${r.detail}`).join(" · "), "err", failed[0].command);
      } else {
        flash(results.map((r) => `${r.action} ${r.service}: ${r.detail}`).join(" · "), "ok", first.command);
      }
    } catch (error) {
      flash(error.message, "err");
    }
    await Promise.all([loadPorts(), currentTab === "services" ? loadServices() : null]);
  }

  document.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const target = JSON.parse(button.dataset.target);
    runAction(button.dataset.action, target, target.service || `port ${target.port}`);
  });

  // -- ports ---------------------------------------------------------
  async function loadPorts() {
    const panel = $("ports-panel");
    const scanning = $("do-scan").checked;
    const spec = $("scan-ports").value.trim();
    panel.innerHTML = `<div class="loading">${scanning ? "Scanning ports…" : "Reading socket table…"}</div>`;
    let rows = [];
    try {
      const query = scanning ? `?scan=${encodeURIComponent(spec || defaultScan)}` : "";
      const { payload } = await api("/api/ports" + query);
      rows = payload.ports || [];
    } catch (error) {
      panel.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
      return;
    }
    if ($("only-exposed").checked) rows = rows.filter((r) => r.scope !== "loopback");
    if (!rows.length) {
      panel.innerHTML = '<div class="empty">No listening sockets matched.</div>';
      return;
    }
    panel.innerHTML = `<table><thead><tr>
        <th>Port</th><th>Proto</th><th>Address</th><th>Exposure</th><th>Process</th>
        <th>Service</th><th>Running</th><th>At boot</th><th>Control</th>
      </tr></thead><tbody>${rows.map((row) => {
        const service = row.service;
        const exposure = row.scope === "loopback"
          ? '<span class="pill">loopback</span>'
          : `<span class="pill exposed">${esc(row.scope)}</span>`;
        const name = service
          ? `${esc(service.name)} ${service.protected ? '<span class="pill lock">protected</span>' : ""}`
          : '<span class="pill">unmanaged</span>';
        return `<tr>
          <td class="mono">${esc(row.port)}</td>
          <td class="mono">${esc(row.proto)}</td>
          <td class="mono">${esc(row.address)}</td>
          <td>${exposure}</td>
          <td class="mono">${esc(row.process)}${row.pid ? " · " + esc(row.pid) : ""}</td>
          <td>${name}</td>
          <td>${service ? yesNo(service.running) : "—"}</td>
          <td>${service ? yesNo(service.enabled) : "—"}</td>
          <td>${service ? actionButtons({ service: service.name }, service) : "—"}</td>
        </tr>`;
      }).join("")}</tbody></table>`;
  }

  // -- services ------------------------------------------------------
  async function loadServices() {
    const panel = $("services-panel");
    panel.innerHTML = '<div class="loading">Loading services…</div>';
    let items = [];
    try {
      const query = new URLSearchParams();
      if ($("service-all").checked) query.set("all", "1");
      const filter = $("service-filter").value.trim();
      if (filter) query.set("filter", filter);
      const { payload } = await api("/api/services?" + query);
      items = payload.services || [];
    } catch (error) {
      panel.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
      return;
    }
    if (!items.length) {
      panel.innerHTML = '<div class="empty">No services matched.</div>';
      return;
    }
    panel.innerHTML = `<table><thead><tr>
        <th>Service</th><th>Running</th><th>At boot</th><th>Description</th><th>Control</th>
      </tr></thead><tbody>${items.map((service) => `<tr>
          <td class="mono">${esc(service.name)} ${service.protected ? '<span class="pill lock">protected</span>' : ""}</td>
          <td>${yesNo(service.running)}</td>
          <td>${yesNo(service.enabled)}</td>
          <td>${esc((service.description || service.display || "").slice(0, 80))}</td>
          <td>${actionButtons({ service: service.name }, service)}</td>
        </tr>`).join("")}</tbody></table>`;
  }

  // -- history -------------------------------------------------------
  async function loadAudit() {
    const panel = $("audit-panel");
    panel.innerHTML = '<div class="loading">Loading history…</div>';
    let entries = [];
    try {
      const { payload } = await api("/api/audit?limit=100");
      entries = payload.entries || [];
    } catch (error) {
      panel.innerHTML = `<div class="empty">${esc(error.message)}</div>`;
      return;
    }
    if (!entries.length) {
      panel.innerHTML = '<div class="empty">No changes recorded yet.</div>';
      return;
    }
    panel.innerHTML = `<table><thead><tr>
        <th>When</th><th>Action</th><th>Service</th><th>Result</th><th>Command</th>
      </tr></thead><tbody>${entries.map((entry) => `<tr>
          <td class="mono">${esc(entry.ts)}</td>
          <td>${esc(entry.action)}${entry.dry_run ? ' <span class="pill">dry run</span>' : ""}</td>
          <td class="mono">${esc(entry.service)}</td>
          <td>${entry.ok ? '<span class="pill on">ok</span>' : '<span class="pill exposed">failed</span>'} ${esc(entry.detail || "")}</td>
          <td class="mono">${esc(entry.command || "")}</td>
        </tr>`).join("")}</tbody></table>`;
  }

  // -- tabs & wiring -------------------------------------------------
  let currentTab = "ports";
  const views = { ports: loadPorts, services: loadServices, audit: loadAudit };
  Object.keys(views).forEach((name) => {
    $("tab-" + name).addEventListener("click", () => {
      currentTab = name;
      Object.keys(views).forEach((other) => {
        $("tab-" + other).setAttribute("aria-selected", String(other === name));
        $("view-" + other).hidden = other !== name;
      });
      views[name]();
    });
  });

  $("refresh-ports").addEventListener("click", loadPorts);
  $("refresh-services").addEventListener("click", loadServices);
  $("refresh-audit").addEventListener("click", loadAudit);
  $("only-exposed").addEventListener("change", loadPorts);
  $("do-scan").addEventListener("change", (event) => {
    $("scan-ports").disabled = !event.target.checked;
    loadPorts();
  });
  $("service-all").addEventListener("change", loadServices);
  let filterTimer;
  $("service-filter").addEventListener("input", () => {
    clearTimeout(filterTimer);
    filterTimer = setTimeout(loadServices, 250);
  });

  let defaultScan = "1-1024";
  (async () => {
    if (!token) {
      $("env").textContent = "no access token — reopen the URL printed by `portwarden serve`";
      $("ports-panel").innerHTML = '<div class="empty">No access token in this tab.</div>';
      return;
    }
    try {
      const { payload } = await api("/api/env");
      defaultScan = payload.default_scan_ports || defaultScan;
      $("scan-ports").value = defaultScan;
      $("env").textContent =
        `${payload.system} · ${payload.backend} (${payload.scope})` +
        `${payload.sudo ? " · sudo" : ""}${payload.dry_run ? " · DRY RUN" : ""}`;
      if (payload.backend === "none") {
        flash("No service manager was detected: ports are listed, but nothing can be switched.", "err");
      }
    } catch (error) {
      $("env").textContent = error.message;
    }
    loadPorts();
  })();
})();
