/**
 * dashboard.js
 *
 * Polls GET /metrics every 5 seconds and updates the dashboard UI.
 * No external libraries required.
 */

const POLL_INTERVAL_MS = 5000;

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

// Max network speed used to calculate bar width (Mbps).
const MAX_NETWORK_MBPS = 1000;

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Clamp value to [0, 100] and return a CSS percentage string.
 * @param {number} value
 * @returns {string}
 */
function toPercent(value) {
    return Math.min(100, Math.max(0, value)).toFixed(1);
}

/**
 * Return CSS colour class based on usage percentage.
 * @param {number} pct
 * @returns {string}
 */
function colorClass(pct) {
    if (pct >= 90) return "critical";
    if (pct >= 70) return "warning";
    return "";
}

/**
 * Update a progress bar element.
 * @param {HTMLElement} barEl
 * @param {number}      pct  value in 0–100
 */
function setBar(barEl, pct) {
    const clamped = Math.min(100, Math.max(0, pct));
    barEl.style.width = clamped + "%";
    barEl.classList.remove("warning", "critical");
    const cls = colorClass(clamped);
    if (cls) barEl.classList.add(cls);
}

// ─── Data rendering ───────────────────────────────────────────────────────────

/**
 * Render a {@link Metrics} JSON object onto the page.
 * @param {{ cpu: number, ram: number, disk: number,
 *            networkIn: number, networkOut: number,
 *            timestamp: number }} data
 */
function render(data) {
    // CPU
    cpuValue.textContent = toPercent(data.cpu) + " %";
    setBar(cpuBar, data.cpu);

    // RAM
    ramValue.textContent = toPercent(data.ram) + " %";
    setBar(ramBar, data.ram);

    // Disk
    diskValue.textContent = toPercent(data.disk) + " %";
    setBar(diskBar, data.disk);

    // Network IN
    netInValue.textContent  = data.networkIn.toFixed(2)  + " Mbps";
    setBar(netInBar,  (data.networkIn  / MAX_NETWORK_MBPS) * 100);

    // Network OUT
    netOutValue.textContent = data.networkOut.toFixed(2) + " Mbps";
    setBar(netOutBar, (data.networkOut / MAX_NETWORK_MBPS) * 100);

    // Timestamp
    const ts = data.timestamp ? new Date(data.timestamp * 1000) : new Date();
    lastUpdated.textContent = "Last update: " + ts.toLocaleTimeString();
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

// ─── AJAX polling loop ────────────────────────────────────────────────────────

async function fetchMetrics() {
    try {
        const response = await fetch("/metrics");
        if (!response.ok) {
            throw new Error("HTTP " + response.status);
        }
        const data = await response.json();
        render(data);
        setOnline();
    } catch (err) {
        console.error("Failed to fetch metrics:", err);
        setOffline();
    }
}

// Initial fetch immediately, then repeat every POLL_INTERVAL_MS.
fetchMetrics();
setInterval(fetchMetrics, POLL_INTERVAL_MS);
