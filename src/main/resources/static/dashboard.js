/**
 * dashboard.js
 *
 * Subscribes to GET /metrics/stream (Server-Sent Events) and renders
 * one panel per host. Host panels are created dynamically on first data.
 */

// Max network speed used to calculate bar width (Mbps).
const MAX_NETWORK_MBPS = 1000;
// Keep last N points. With 5s updates: 60 points ≈ 5 minutes.
const MAX_POINTS = 60;

// Optional: reconnect backoff (ms)
let reconnectDelayMs = 1000;
const RECONNECT_DELAY_MAX_MS = 15000;

// ─── Per-host state ────────────────────────────────────────────────────────────
// hostState[hostName] = { elements: {…}, series: {…} }
const hostState = {};

// ─── Helpers ──────────────────────────────────────────────────────────────────
function toPercent(value) {
    return Math.min(100, Math.max(0, value)).toFixed(1);
}

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

function slugify(name) {
    return name.replace(/[^a-zA-Z0-9]/g, "-").toLowerCase();
}

// ─── Host panel creation ───────────────────────────────────────────────────────
function createHostPanel(hostName) {
    const id = slugify(hostName);
    const container = document.getElementById("hosts-container");

    const section = document.createElement("section");
    section.className = "host-panel";
    section.id = "host-" + id;
    section.innerHTML = `
        <h2 class="host-title">&#x1F5A5;&#xFE0F; ${hostName}</h2>
        <div class="host-metrics">
            <!-- CPU -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x2699;&#xFE0F;</span>
                    <span class="metric-label">CPU Usage</span>
                    <span id="${id}-cpu-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${id}-cpu-bar" class="progress-bar cpu"></div>
                </div>
            </div>
            <!-- RAM -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x1F9E0;</span>
                    <span class="metric-label">Memory Usage</span>
                    <span id="${id}-ram-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${id}-ram-bar" class="progress-bar ram"></div>
                </div>
            </div>
            <!-- DISK -->
            <div class="metric-card">
                <div class="metric-header">
                    <span class="metric-icon">&#x1F4BE;</span>
                    <span class="metric-label">Disk Usage</span>
                    <span id="${id}-disk-value" class="metric-value">--%</span>
                </div>
                <div class="progress-bar-track">
                    <div id="${id}-disk-bar" class="progress-bar disk"></div>
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
                        <span id="${id}-net-in-value" class="metric-value">-- Mbps</span>
                    </div>
                    <div class="net-divider"></div>
                    <div class="net-direction">
                        <span class="net-arrow up">&#x25B2;</span>
                        <span class="net-dir-label">OUT</span>
                        <span id="${id}-net-out-value" class="metric-value">-- Mbps</span>
                    </div>
                </div>
                <div class="progress-bar-track">
                    <div id="${id}-net-in-bar"  class="progress-bar net-in"  title="Network IN"></div>
                </div>
                <div class="progress-bar-track" style="margin-top:6px;">
                    <div id="${id}-net-out-bar" class="progress-bar net-out" title="Network OUT"></div>
                </div>
            </div>
        </div>
        <section class="graphs">
            <h3>Graphs (last ~5 minutes)</h3>
            <div class="graph">
                <div class="graph-title">CPU (%)</div>
                <canvas id="${id}-cpu-chart" width="800" height="120"></canvas>
            </div>
            <div class="graph">
                <div class="graph-title">RAM (%)</div>
                <canvas id="${id}-ram-chart" width="800" height="120"></canvas>
            </div>
            <div class="graph">
                <div class="graph-title">Disk (%)</div>
                <canvas id="${id}-disk-chart" width="800" height="120"></canvas>
            </div>
            <div class="graph">
                <div class="graph-title">Network IN (Mbps)</div>
                <canvas id="${id}-netin-chart" width="800" height="120"></canvas>
            </div>
            <div class="graph">
                <div class="graph-title">Network OUT (Mbps)</div>
                <canvas id="${id}-netout-chart" width="800" height="120"></canvas>
            </div>
        </section>
    `;
    container.appendChild(section);

    hostState[hostName] = {
        elements: {
            cpuValue:    document.getElementById(`${id}-cpu-value`),
            cpuBar:      document.getElementById(`${id}-cpu-bar`),
            ramValue:    document.getElementById(`${id}-ram-value`),
            ramBar:      document.getElementById(`${id}-ram-bar`),
            diskValue:   document.getElementById(`${id}-disk-value`),
            diskBar:     document.getElementById(`${id}-disk-bar`),
            netInValue:  document.getElementById(`${id}-net-in-value`),
            netInBar:    document.getElementById(`${id}-net-in-bar`),
            netOutValue: document.getElementById(`${id}-net-out-value`),
            netOutBar:   document.getElementById(`${id}-net-out-bar`),
            cpuChart:    document.getElementById(`${id}-cpu-chart`),
            ramChart:    document.getElementById(`${id}-ram-chart`),
            diskChart:   document.getElementById(`${id}-disk-chart`),
            netInChart:  document.getElementById(`${id}-netin-chart`),
            netOutChart: document.getElementById(`${id}-netout-chart`),
        },
        series: { cpu: [], ram: [], disk: [], netIn: [], netOut: [] }
    };
}

// ─── Data rendering ────────────────────────────────────────────────────────────
function renderHost(data) {
    const hostName = data.hostName || "Unknown";
    if (!hostState[hostName]) {
        createHostPanel(hostName);
    }

    const { elements, series } = hostState[hostName];

    elements.cpuValue.textContent  = toPercent(data.cpu) + " %";
    setBar(elements.cpuBar, data.cpu);

    elements.ramValue.textContent  = toPercent(data.ram) + " %";
    setBar(elements.ramBar, data.ram);

    elements.diskValue.textContent = toPercent(data.disk) + " %";
    setBar(elements.diskBar, data.disk);

    elements.netInValue.textContent  = data.networkIn.toFixed(2)  + " Mbps";
    setBar(elements.netInBar,  (data.networkIn  / MAX_NETWORK_MBPS) * 100);

    elements.netOutValue.textContent = data.networkOut.toFixed(2) + " Mbps";
    setBar(elements.netOutBar, (data.networkOut / MAX_NETWORK_MBPS) * 100);

    pushPoint(series.cpu,    data.cpu      ?? 0);
    pushPoint(series.ram,    data.ram      ?? 0);
    pushPoint(series.disk,   data.disk     ?? 0);
    pushPoint(series.netIn,  data.networkIn  ?? 0);
    pushPoint(series.netOut, data.networkOut ?? 0);

    redrawHostCharts(elements, series);
}

function render(dataList) {
    if (!Array.isArray(dataList)) return;
    for (const data of dataList) {
        renderHost(data);
    }

    const ts = dataList[0]?.timestamp
        ? new Date(dataList[0].timestamp * 1000)
        : new Date();
    document.getElementById("last-updated").textContent =
        "Last update: " + ts.toLocaleTimeString();

    console.log("render", dataList);
}

// ─── Charts ────────────────────────────────────────────────────────────────────
function pushPoint(arr, value) {
    arr.push(value);
    if (arr.length > MAX_POINTS) arr.shift();
}

function drawLineChart(canvas, values, opts) {
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    const w = canvas.width, h = canvas.height;

    const minY  = opts.minY  ?? 0;
    const maxY  = opts.maxY  ?? 100;
    const color = opts.color ?? "#3ddc97";
    const label = opts.label ?? "";

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
    ctx.fillText(label, 8, 16);

    if (!values.length) return;

    const clamp = (v) => Math.min(maxY, Math.max(minY, v));
    const n  = values.length;
    const dx = n === 1 ? 0 : (w - 16) / (n - 1);
    const x0 = 8;

    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();

    for (let i = 0; i < n; i++) {
        const v = clamp(values[i]);
        const x = x0 + i * dx;
        const t = (v - minY) / (maxY - minY || 1);
        const y = h - 8 - t * (h - 24);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();

    const last = clamp(values[n - 1]);
    ctx.fillStyle = "#bbb";
    ctx.fillText(String(last.toFixed(2)), w - 70, 16);
}

function redrawHostCharts(elements, series) {
    drawLineChart(elements.cpuChart,    series.cpu,    { minY: 0, maxY: 100,             color: "#4ade80", label: "CPU %" });
    drawLineChart(elements.ramChart,    series.ram,    { minY: 0, maxY: 100,             color: "#60a5fa", label: "RAM %" });
    drawLineChart(elements.diskChart,   series.disk,   { minY: 0, maxY: 100,             color: "#fbbf24", label: "Disk %" });
    drawLineChart(elements.netInChart,  series.netIn,  { minY: 0, maxY: MAX_NETWORK_MBPS, color: "#a78bfa", label: "Net IN Mbps" });
    drawLineChart(elements.netOutChart, series.netOut, { minY: 0, maxY: MAX_NETWORK_MBPS, color: "#f472b6", label: "Net OUT Mbps" });
}

// ─── Connection status helpers ─────────────────────────────────────────────────
const statusDot  = document.getElementById("status-dot");
const statusText = document.getElementById("status-text");

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

function startSse() {
    if (es) { es.close(); es = null; }

    es = new EventSource("/metrics/stream");

    es.onopen = () => {
        setOnline();
        reconnectDelayMs = 1000;
    };

    es.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            render(data);
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