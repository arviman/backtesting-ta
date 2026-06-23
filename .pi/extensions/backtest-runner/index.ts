import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { execSync } from "node:child_process";

// ─────────────────────────────────────────────────────────────────────────────
//  Auto-discover backtest entry points
// ─────────────────────────────────────────────────────────────────────────────

interface BacktestEntry {
  mainClass: string;
  label: string;
  description: string;
}

function findKtFiles(dir: string): string[] {
  const results: string[] = [];
  function walk(current: string) {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith(".kt")) results.push(full);
    }
  }
  walk(dir);
  return results;
}

function discoverBacktests(cwd: string): BacktestEntry[] {
  const srcDir = join(cwd, "src", "main", "kotlin");
  if (!existsSync(srcDir)) return [];

  const result: BacktestEntry[] = [];
  const ktFiles = findKtFiles(srcDir);

  for (const file of ktFiles) {
    try {
      const content = readFileSync(file, "utf-8");

      // Entry point: @JvmStatic + fun main + args: Array<String>
      const hasMain =
        content.includes("@JvmStatic") &&
        /fun\s+main\s*\(\s*args\s*:\s*Array\s*<\s*String\s*>/.test(content);

      if (!hasMain) continue;

      const pkgMatch = content.match(/^package\s+([\w.]+)/m);
      const pkg = pkgMatch ? pkgMatch[1] : "";

      // Look for object or class declaration
      const objMatch = content.match(/^(?:object|class)\s+(\w+)/m);
      if (!objMatch) continue;
      const className = objMatch[1];

      // Skip private/internal (test helpers, etc.)
      if (content.match(new RegExp(`^private\\s+(?:object|class)\\s+${className}`, "m")))
        continue;

      const mainClass = pkg ? `${pkg}.${className}` : className;

      // Extract a top-line description from KDoc or leading comment
      const objIdx = content.indexOf(`object ${className}`);
      const before = objIdx >= 0 ? content.substring(0, objIdx) : "";
      const kdocMatch = before.match(/\/\*\*\s*\n\s*\*\s*(.+)/);
      const simpleComment = before.match(/\/\/\s*(.+)/);
      const docLine = kdocMatch
        ? kdocMatch[1].trim()
        : simpleComment
          ? simpleComment[1].trim()
          : `${className} — no description`;

      const relPath = relative(srcDir, file).replace(/\\/g, "/");
      const label = relPath.replace(/\.kt$/, "");

      result.push({ mainClass, label, description: docLine });
    } catch {
      // skip unreadable files
    }
  }

  return result;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cache
// ─────────────────────────────────────────────────────────────────────────────

let cachedEntries: BacktestEntry[] | null = null;
let cachedCwd = "";

function getBacktests(cwd: string): BacktestEntry[] {
  if (cachedEntries && cachedCwd === cwd) return cachedEntries;
  cachedCwd = cwd;
  cachedEntries = discoverBacktests(cwd);
  return cachedEntries;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Parse report metrics from stdout
// ─────────────────────────────────────────────────────────────────────────────

function parseReportMetrics(stdout: string): Record<string, string> {
  const metrics: Record<string, string> = {};
  for (const line of stdout.split("\n")) {
    const m = line.match(/^\s*([\w\s/&-]+?)\s*:\s+(.+?)\s*$/);
    if (m) {
      const key = m[1].trim();
      // Only capture report-style lines, not log lines
      if (
        key.length > 5 &&
        !key.startsWith("Bars") &&
        !key.startsWith("OS bars") &&
        !key.startsWith("Sweeping") &&
        !key.startsWith("Build") &&
        !key.startsWith(">")
      ) {
        metrics[key] = m[2].trim();
      }
    }
  }
  return metrics;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Format sweep tables into a concise summary
// ─────────────────────────────────────────────────────────────────────────────

function summarizeSweep(stdout: string): string {
  const lines = stdout.split("\n");
  // If the output has a columnar data table, extract the top/bottom rows
  const dataRows = lines.filter(
    (l) => l.match(/\d+\.\d+[%x]/) || l.match(/^\S.*\|\s+\d+/),
  );
  if (dataRows.length <= 20) return ""; // small enough, keep full output
  // Return first 5 + summary + last 5
  const head = dataRows.slice(0, 5);
  const tail = dataRows.slice(-5);
  return (
    [dataRows[0]] // header
      .concat(head)
      .concat([`... (${dataRows.length - 11} rows omitted) ...`])
      .concat(tail)
      .join("\n")
  );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Extension
// ─────────────────────────────────────────────────────────────────────────────

export default function (pi: ExtensionAPI) {
  // ── Tool: list available backtests ─────────────────────────────────────

  pi.registerTool({
    name: "list_backtests",
    label: "List Backtests",
    description:
      "List all available backtest/demo entry points in this project. " +
      "Each is a Kotlin main class that runs a backtest or parameter sweep.",
    parameters: Type.Object({}),
    async execute(_toolCallId, _params, _signal, _onUpdate, ctx) {
      const entries = getBacktests(ctx.cwd);
      if (entries.length === 0) {
        return {
          content: [{ type: "text", text: "No backtest entry points found." }],
        };
      }
      const lines = entries.map(
        (e, i) =>
          `${i + 1}. **${e.label}**\n` +
          `   Class: \`${e.mainClass}\`\n` +
          `   ${e.description}`,
      );
      return {
        content: [{ type: "text", text: lines.join("\n\n") }],
      };
    },
  });

  // ── Tool: run a backtest ───────────────────────────────────────────────

  pi.registerTool({
    name: "run_backtest",
    label: "Run Backtest",
    description:
      "Run a Kotlin backtest/demo via Gradle. " +
      "Use `list_backtests` first to see available entry points. " +
      "Pass `mainClass` to run a specific one; omit to run the default. " +
      "Builds take 30-90s depending on changes.",
    parameters: Type.Object({
      mainClass: Type.Optional(
        Type.String({
          description:
            "Fully qualified main class, e.g. 'com.arviman.ta.BackTestDemo'. " +
            "Omit to run the default (SqueezeMomentumLeveragedTest).",
        }),
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate, ctx) {
      const mainClass = params.mainClass || "";
      const label = mainClass || "default";

      onUpdate?.({
        content: [{ type: "text", text: `Building & running: ${label}...` }],
      });

      const gradleArgs = mainClass
        ? `-PmainClass=${mainClass}`
        : "";
      const cmd = `./gradlew run ${gradleArgs} -q --no-daemon`;

      let stdout = "";
      let stderr = "";
      let exitCode = 0;
      let timedOut = false;

      try {
        onUpdate?.({
          content: [{ type: "text", text: "Compiling & running backtest (may take 30-90s)…" }],
        });

        stdout = execSync(cmd, {
          encoding: "utf-8",
          timeout: 120_000,
          maxBuffer: 10 * 1024 * 1024,
          signal: signal ?? undefined,
          cwd: ctx.cwd,
          env: { ...process.env },
        });
      } catch (e: any) {
        stdout = e.stdout || "";
        stderr = e.stderr || e.message || "";
        exitCode = e.status || 1;
        if (e.code === "ETIMEDOUT") timedOut = true;
      }

      // Build result
      const parts: string[] = [];

      if (timedOut) {
        parts.push("### ⏱️ Timed out after 2 minutes\n");
        parts.push("The backtest took too long. Try a smaller dataset or simpler strategy.\n");
      }

      if (exitCode !== 0 && stderr) {
        parts.push(`### Build/Run Error (exit ${exitCode})`);
        parts.push("```");
        parts.push(stderr.substring(0, 5000));
        parts.push("```");
      }

      if (stdout.trim()) {
        // Special handling for parameter sweeps
        const isSweep =
          stdout.includes("Sweeping") || stdout.includes("sweep");
        const summarized = isSweep ? summarizeSweep(stdout) : "";
        const useSummarized = summarized.length > 0 && summarized.length < stdout.length;

        // Parse metrics if present
        const metrics = parseReportMetrics(stdout);
        if (Object.keys(metrics).length > 0) {
          parts.push("### Key Metrics");
          for (const [k, v] of Object.entries(metrics)) {
            parts.push(`- **${k}**: ${v}`);
          }
          parts.push("");
        }

        parts.push("### Output");
        parts.push("```");
        parts.push(useSummarized ? summarized : stdout.substring(0, 15000));
        parts.push("```");
      } else if (exitCode === 0) {
        parts.push("Backtest completed with no output.");
      }

      const resultText = parts.join("\n");
      return {
        content: [{ type: "text", text: resultText || "Backtest finished." }],
        details: { exitCode, label },
      };
    },
  });

  // ── Status line on session start ───────────────────────────────────────

  pi.on("session_start", async (_event, ctx) => {
    const entries = getBacktests(ctx.cwd);
    if (entries.length > 0) {
      ctx.ui.setStatus("backtest", `${entries.length} backtests ready`);
    }
  });
}
