/**
 * dashboard.js
 *
 * Subscribes to GET /metrics/stream (Server-Sent Events) and updates the dashboard UI.
 * No external libraries required.
 */

// ─── DOM references ───────────────────────────────────────────────────────────
const cpuValue    = document.getElementById("cpu-value");
const cpuBar      = document.getElementById("cpu-bar");
const ramValue    = document.getElementById("ram-value");
const ramBar      = document.getElementById("ram-bar");
const diskValue   = document.getElementById("disk-value");
const diskBar     = document.getElementById("disk-bar");
const netInValue  = document.getElementById("net-in-value");
const netInBar    = document.getElementById("net-in-bar");
const netOutValue = document.getElementById("net-out-value");
const netOutBar   = document.getElementById("net-out-bar");
const lastUpdated = document.getElementById("last-updated");
const statusDot   = document.getElementById("status-dot");
const statusText  = document.getElementById("status-text");
const cpuChart = document.getElementById("cpu-chart");
const ramChart = document.getElementById("ram-chart");
const diskChart = document.getElementById("disk-chart");
const netInChart = document.getElementById("netin-chart");
const netOutChart = document.getElementById("netout-chart");

// Max network speed used to calculate bar width (Mbps).
const MAX_NETWORK_MBPS = 1000;

// Optional: reconnect backoff (ms)
let reconnectDelayMs = 1000;
const RECONNECT_DELAY_MAX_MS = 15000;

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

// ─── Data rendering ───────────────────────────────────────────────────────────
function render(data) {
    cpuValue.textContent = toPercent(data.cpu) + " %";
    setBar(cpuBar, data.cpu);

    ramValue.textContent = toPercent(data.ram) + " %";
    setBar(ramBar, data.ram);

    diskValue.textContent = toPercent(data.disk) + " %";
    setBar(diskBar, data.disk);

    netInValue.textContent  = data.networkIn.toFixed(2) + " Mbps";
    setBar(netInBar, (data.networkIn / MAX_NETWORK_MBPS) * 100);

    netOutValue.textContent = data.networkOut.toFixed(2) + " Mbps";
    setBar(netOutBar, (data.networkOut / MAX_NETWORK_MBPS) * 100);

    const ts = data.timestamp ? new Date(data.timestamp * 1000) : new Date();
    lastUpdated.textContent = "Last update: " + ts.toLocaleTimeString();

    pushPoint(series.cpu, data.cpu ?? 0);
    pushPoint(series.ram, data.ram ?? 0);
    pushPoint(series.disk, data.disk ?? 0);
    pushPoint(series.netIn, data.networkIn ?? 0);
    pushPoint(series.netOut, data.networkOut ?? 0);
    redrawCharts();
}

// ─── Connection status helpers ────────────────────────────────────────────────
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

// ─── SSE connection ───────────────────────────────────────────────────────────
let es = null;

function startSse() {
    if (es) {
        es.close();
        es = null;
    }

    es = new EventSource("/metrics/stream");

    es.onopen = () => {
        // Connection established (might still not have received a message yet)
        setOnline();
        reconnectDelayMs = 1000; // reset backoff
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

        // Some browsers auto-reconnect, but it's not always reliable across proxies.
        // We'll do our own reconnect with backoff.
        try { es.close(); } catch (_) {}
        es = null;

        setTimeout(startSse, reconnectDelayMs);
        reconnectDelayMs = Math.min(RECONNECT_DELAY_MAX_MS, reconnectDelayMs * 2);
    };
}
// Keep last N points. With 5s updates: 60 points ≈ 5 minutes.
const MAX_POINTS = 60;

const series = {
  cpu: [],
  ram: [],
  disk: [],
  netIn: [],
  netOut: []
};

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
  const label = opts.label ?? "";

  // Clear
  ctx.clearRect(0, 0, w, h);

  // Background grid
  ctx.fillStyle = "#111";
  ctx.fillRect(0, 0, w, h);

  ctx.strokeStyle = "#222";
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = (h * i) / 4;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(w, y);
    ctx.stroke();
  }

  // Label
  ctx.fillStyle = "#bbb";
  ctx.font = "12px system-ui, sans-serif";
  ctx.fillText(label, 8, 16);

  if (!values.length) return;

  // Clamp helper
  const clamp = (v) => Math.min(maxY, Math.max(minY, v));

  // Plot
  const n = values.length;
  const dx = n === 1 ? 0 : (w - 16) / (n - 1);
  const x0 = 8;

  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  ctx.beginPath();

  for (let i = 0; i < n; i++) {
    const v = clamp(values[i]);
    const x = x0 + i * dx;
    const t = (v - minY) / (maxY - minY || 1); // 0..1
    const y = h - 8 - t * (h - 24);            // leave padding for label
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();

  // Last value
  const last = clamp(values[n - 1]);
  ctx.fillStyle = "#bbb";
  ctx.fillText(String(last.toFixed(2)), w - 70, 16);
}

function redrawCharts() {
  drawLineChart(cpuChart, series.cpu,   { minY: 0, maxY: 100, color: "#4ade80", label: "CPU %" });
  drawLineChart(ramChart, series.ram,   { minY: 0, maxY: 100, color: "#60a5fa", label: "RAM %" });
  drawLineChart(diskChart, series.disk, { minY: 0, maxY: 100, color: "#fbbf24", label: "Disk %" });

  // Scale network charts to your MAX_NETWORK_MBPS constant
  drawLineChart(netInChart,  series.netIn,  { minY: 0, maxY: MAX_NETWORK_MBPS, color: "#a78bfa", label: "Net IN Mbps" });
  drawLineChart(netOutChart, series.netOut, { minY: 0, maxY: MAX_NETWORK_MBPS, color: "#f472b6", label: "Net OUT Mbps" });
}

startSse();