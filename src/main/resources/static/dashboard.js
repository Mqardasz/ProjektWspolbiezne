/**
 * dashboard.js
 *
 * Subscribes to GET /metrics/hosts/stream (Server-Sent Events) and dynamically
 * builds a panel for each host, updating it whenever new data arrives.
 */

const MAX_NETWORK_MBPS = 1000;
const MAX_POINTS = 60; // ~5 minutes at 5 s interval

const hostsContainer = document.getElementById("hosts-container");
const lastUpdated    = document.getElementById("last-updated");
const statusDot      = document.getElementById("status-dot");
const statusText     = document.getElementById("status-text");

// ─── Per-host state ────────────────────────────────────────────────────────────
const hostState = {}; // { hostName: { elements, series } }

function ensureHostPanel(hostName) {
    if (hostState[hostName]) return;

    const safeId = hostName.replace(/\s+/g, "-").replace(/[^a-zA-Z0-9\-]/g, "");

    const panel = document.createElement("section");
    panel.className = "host-panel";
    panel.id = "host-" + safeId;
    panel.innerHTML = `
        <h2 class="host-title">&#x1F5A5;&#xFE0F; ${escapeHtml(hostName)}</h2>
        <div class="metrics-row">
            <!-- CPU -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x2699;&#xFE0F;</span>
                    <span class="metric-label">CPU Usage</span>
                    <span id="${safeId}-cpu-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${safeId}-cpu-bar" class="progress-bar cpu"></div>
                </div>
            </div>
            <!-- RAM -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x1F9E0;</span>
                    <span class="metric-label">Memory Usage</span>
                    <span id="${safeId}-ram-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${safeId}-ram-bar" class="progress-bar ram"></div>
                </div>
            </div>
            <!-- DISK -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x1F4BE;</span>
                    <span class="metric-label">Disk Usage</span>
                    <span id="${safeId}-disk-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${safeId}-disk-bar" class="progress-bar disk"></div>
                </div>
            </div>
            <!-- NETWORK -->
            <div class="metric-card network-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x1F4F6;</span>
                    <span class="metric-label">Network</span>
                </div>
                <div class="network-row">
                    <div class="net-direction">
                        <span class="net-arrow">&#x25BC;</span>
                        <span class="net-dir-label">IN</span>
                        <span id="${safeId}-net-in-value" class="metric-value">-- Mbps</span>
                    </div>
                    <div class="net-divider"></div>
                    <div class="net-direction">
                        <span class="net-arrow up">&#x25B2;</span>
                        <span class="net-dir-label">OUT</span>
                        <span id="${safeId}-net-out-value" class="metric-value">-- Mbps</span>
                    </div>
                </div>
                <div class="progress-bar-track">
                    <div id="${safeId}-net-in-bar"  class="progress-bar net-in"  title="Network IN"></div>
                </div>
                <div class="progress-bar-track" style="margin-top:6px;">
                    <div id="${safeId}-net-out-bar" class="progress-bar net-out" title="Network OUT"></div>
                </div>
            </div>
        </div>
        <div class="graphs">
            <div class="graph">
                <div class="graph-title">CPU (%)</div>
                <canvas id="${safeId}-cpu-chart" width="800" height="120"></canvas>
            </div>
            <div class="graph">
                <div class="graph-title">RAM (%)</div>
                <canvas id="${safeId}-ram-chart" width="800" height="120"></canvas>
            </div>
        </div>`;

    hostsContainer.appendChild(panel);

    hostState[hostName] = {
        el: {
            cpuValue:    document.getElementById(safeId + "-cpu-value"),
            cpuBar:      document.getElementById(safeId + "-cpu-bar"),
            ramValue:    document.getElementById(safeId + "-ram-value"),
            ramBar:      document.getElementById(safeId + "-ram-bar"),
            diskValue:   document.getElementById(safeId + "-disk-value"),
            diskBar:     document.getElementById(safeId + "-disk-bar"),
            netInValue:  document.getElementById(safeId + "-net-in-value"),
            netInBar:    document.getElementById(safeId + "-net-in-bar"),
            netOutValue: document.getElementById(safeId + "-net-out-value"),
            netOutBar:   document.getElementById(safeId + "-net-out-bar"),
            cpuChart:    document.getElementById(safeId + "-cpu-chart"),
            ramChart:    document.getElementById(safeId + "-ram-chart"),
        },
        series: { cpu: [], ram: [] }
    };
}

// ─── Rendering ─────────────────────────────────────────────────────────────────
function render(data) {
    const host = data.hostName || "Unknown";
    ensureHostPanel(host);

    const s = hostState[host];
    const el = s.el;

    el.cpuValue.textContent  = toPercent(data.cpu)  + " %";
    setBar(el.cpuBar, data.cpu);

    el.ramValue.textContent  = toPercent(data.ram)  + " %";
    setBar(el.ramBar, data.ram);

    el.diskValue.textContent = toPercent(data.disk) + " %";
    setBar(el.diskBar, data.disk);

    el.netInValue.textContent  = data.networkIn.toFixed(2)  + " Mbps";
    setBar(el.netInBar,  (data.networkIn  / MAX_NETWORK_MBPS) * 100);

    el.netOutValue.textContent = data.networkOut.toFixed(2) + " Mbps";
    setBar(el.netOutBar, (data.networkOut / MAX_NETWORK_MBPS) * 100);

    pushPoint(s.series.cpu, data.cpu  ?? 0);
    pushPoint(s.series.ram, data.ram  ?? 0);
    drawLineChart(el.cpuChart, s.series.cpu, { color: "#4ade80", label: "CPU %" });
    drawLineChart(el.ramChart, s.series.ram, { color: "#60a5fa", label: "RAM %" });

    const ts = data.timestamp ? new Date(data.timestamp * 1000) : new Date();
    lastUpdated.textContent = "Last update: " + ts.toLocaleTimeString();
}

// ─── Helpers ───────────────────────────────────────────────────────────────────
function toPercent(v) { return Math.min(100, Math.max(0, v)).toFixed(1); }

function colorClass(pct) {
    if (pct >= 90) return "critical";
    if (pct >= 70) return "warning";
    return "";
}

function setBar(barEl, pct) {
    const clamped = Math.min(100, Math.max(0, pct));
    barEl.style.width = clamped + "%";
    barEl.classList.remove("warning", "critical");
    const cls = colorClass(clamped);
    if (cls) barEl.classList.add(cls);
}

function pushPoint(arr, value) {
    arr.push(value);
    if (arr.length > MAX_POINTS) arr.shift();
}

function drawLineChart(canvas, values, opts) {
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    const w = canvas.width, h = canvas.height;
    const minY = opts.minY ?? 0;
    const maxY = opts.maxY ?? 100;
    const color = opts.color ?? "#3ddc97";

    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "#111";
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = "#222";
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
        const y = (h * i) / 4;
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
    }
    ctx.fillStyle = "#bbb";
    ctx.font = "12px system-ui, sans-serif";
    ctx.fillText(opts.label ?? "", 8, 16);

    if (!values.length) return;

    const n = values.length;
    const dx = n === 1 ? 0 : (w - 16) / (n - 1);
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (let i = 0; i < n; i++) {
        const v = Math.min(maxY, Math.max(minY, values[i]));
        const x = 8 + i * dx;
        const t = (v - minY) / (maxY - minY || 1);
        const y = h - 8 - t * (h - 24);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();

    const last = values[n - 1];
    ctx.fillStyle = "#bbb";
    ctx.fillText(last.toFixed(2), w - 70, 16);
}

function escapeHtml(str) {
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// ─── Connection status ─────────────────────────────────────────────────────────
function setOnline() {
    statusDot.classList.add("online");
    statusDot.classList.remove("offline");
    statusText.textContent = "Connected";
}

function setOffline() {
    statusDot.classList.remove("online");
    statusDot.classList.add("offline");
    statusText.textContent = "Connection error – retrying…";
}

// ─── SSE connection ────────────────────────────────────────────────────────────
let es = null;
let reconnectDelayMs = 1000;
const RECONNECT_DELAY_MAX_MS = 15000;

function startSse() {
    if (es) { es.close(); es = null; }

    es = new EventSource("/metrics/hosts/stream");

    es.onopen = () => {
        setOnline();
        reconnectDelayMs = 1000;
    };

    es.onmessage = (event) => {
        try {
            render(JSON.parse(event.data));
            setOnline();
        } catch (e) {
            console.error("Failed to parse SSE message:", e, event.data);
        }
    };

    es.onerror = (err) => {
        console.error("SSE error:", err);
        setOffline();
        try { es.close(); } catch (_) {}
        es = null;
        setTimeout(startSse, reconnectDelayMs);
        reconnectDelayMs = Math.min(RECONNECT_DELAY_MAX_MS, reconnectDelayMs * 2);
    };
}

startSse();
