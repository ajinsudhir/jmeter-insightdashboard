# Viewing Dashboard Results Outside a Local GUI Run

The Custom Live Dashboard and its AI Results Summary tab are Swing GUI
components — they can't render during a headless (`-n`) run, since there's
no window to draw to. This guide covers the two situations where you still
want that view: **after** a headless run has already finished, and
**live**, while your load generators are remote Linux machines.

---

## Part 1 — Viewing results after a Non-GUI (`-n`) run

### Steps

**1. Run your test headless, saving results in XML format:**

```bash
jmeter -n -t test.jmx -l results.jtl -Jjmeter.save.saveservice.output_format=xml
```

> **Why `-Jjmeter.save.saveservice.output_format=xml` specifically:** the
> default `.jtl` format is flat CSV, which has no way to encode "this row
> is a sub-request of that row" — that nesting only exists in memory
> during a live run, via `SampleResult.getSubResults()`. XML format
> preserves it as nested `<sample>` elements, and JMeter's file loader
> reconstructs that nesting correctly. If you only care about aggregate
> stats and don't need the transaction/sub-request tree to reconstruct
> correctly, you can skip this flag and use plain CSV.

**2. Open the *same* `test.jmx` in the JMeter GUI.** Just open it — you do
not need to press Run again.

**3. Select the "Custom Live Dashboard" listener** in the test tree.

**4. In its "Write results to file / Read from file" section, click
Browse... and select `results.jtl`** (the file from step 1).

JMeter loads and replays that file through the same pipeline a live run
uses — the stat cards, table, and charts populate as if the test had just
run in front of you.

**5. Switch to the "Results Summary (AI)" tab and click "Generate AI
Summary"** as normal. The data's already there, so this works identically
to a live run.

### If you're also using the Custom HTML Report Listener

No extra steps needed — it already wrote its own `.html` file
automatically the moment the headless run finished, since it's a real
`TestElement` built to work standalone in `-n` mode. Just open that file
directly; the Browse-to-load trick above doesn't apply to it (and isn't
needed).

### Which listener for which situation

| Situation | Use |
|---|---|
| Fully unattended CI/CD pipeline, nobody watching | **Custom HTML Report Listener** — writes its file automatically, no human/GUI step ever needed |
| Reviewing results interactively after a run (headless *or* GUI) | **Custom Live Dashboard** + AI tab — load the `.jtl` as above, then explore/generate the report by hand |

---

## Part 2 — Live view with remote Linux load generators (Distributed Testing)

**Yes, this works** — and it's not specific to this plugin. JMeter's
built-in distributed/remote testing mode is designed exactly for this:
your local GUI acts purely as a controller and result collector, while the
actual sampling happens on remote engines. Samples stream back over RMI
and populate every listener in your local GUI — including Custom Live
Dashboard — live, the same as a local run.

### One-time setup

**On each Linux load generator:**

1. Install the **same JMeter version** as your local controller. Version
   mismatch between controller and workers is the most common cause of
   "it just doesn't work."
2. Copy any files your test plan depends on (CSV Data Set Config files,
   external scripts, etc.) to the **same relative paths** as on your
   controller. Only the `.jmx` itself gets pushed automatically when you
   start remotely — supporting files do not.
3. Start the JMeter server process and leave it running:
   ```bash
   cd apache-jmeter-5.6.3/bin
   ./jmeter-server
   ```

**On your local (controller) machine:**

1. Edit `bin/jmeter.properties` (or `user.properties`) and list your Linux
   workers:
   ```properties
   remote_hosts=<linux-ip-1>,<linux-ip-2>
   ```
2. Since JMeter 4.0, remote testing uses RMI over SSL by default, which
   needs a shared keystore. Generate it once on the controller:
   ```bash
   create-rmi-keystore.bat
   ```
   Then copy the resulting `bin/rmi_keystore.jks` to the `bin/` folder of
   **every** worker machine (and the controller itself needs it too).

### Running it

1. Launch JMeter normally — GUI mode, **not** `-n`:
   ```bash
   jmeter.bat
   ```
2. Open your test plan with Custom Live Dashboard added as a listener, as
   usual.
3. **Run menu → Remote Start All** (or **Remote Start → *one host***
   first, to confirm connectivity before committing to all of them).
4. Watch the dashboard update live as samples stream back from the Linux
   engines.
5. **Run menu → Remote Stop All** when you're done, or let it finish
   naturally.

### Troubleshooting

- **RMI/SSL certificate errors** — simplest fix for a trusted internal
  network: add `server.rmi.ssl.disable=true` to `jmeter.properties` on
  **both** the controller and every worker, then restart everything. This
  disables encryption in transit, so only do it on networks you trust.
- **Firewall blocking the connection** — RMI needs two-way connectivity:
  controller → worker (default port 1099, fix it with
  `server.rmi.localport=4000` on the worker) **and** worker → controller
  callback (fix it with `client.rmi.localport=5000` on the controller), so
  your network team can open two exact ports instead of a wide ephemeral
  range.
- **Version mismatch** — keep the JMeter version identical everywhere.

### Worth knowing before you rely on this

- **Thread Group settings apply to each remote engine independently.**
  Two workers configured for 100 threads each means 200 total virtual
  users, not 100 split between them.
- **Remote/GUI mode uses more controller-side resources** than a pure
  `-n` run on the same machine, since the controller is now also
  receiving, aggregating, and rendering every sample live — on top of
  whatever else that machine is doing. For very high aggregate
  throughput, keep an eye on the controller's CPU; if it becomes the
  bottleneck, fall back to Part 1 instead (workers write results locally
  with `-l`, pull the files afterward, load them for review) rather than
  streaming everything live.
