## SmartBlitz Insight Dashboard for JMeter v1.0.0

First public release.

- **Live dashboard.** Stat cards, response-time, throughput and error-rate charts and a response-code breakdown, updating while the test runs.
- **Per-request tree table.** Expand each transaction to see its individual requests, with Avg, Max, P90, P95 and P99, throughput and SLA highlighting. Filter, sort and copy or export to CSV and Excel.
- **Request drill-down.** Select a row for the latest sample: Sampler Result, Request and Response Data with Headers, Body and Cookies sub-tabs, Raw, Pretty or Render HTML view, find, and extracted variables.
- **Find Dynamic Values and correlation hand-off.** Scan a sample for likely dynamic values and create a correlation rule for [Correlation Studio](https://github.com/ajinsudhir/jmeter-correlationstudio) directly from the dashboard.
- **Remote Run.** Run the open test plan on a remote Linux host over SSH (password or key), upload the `.jmx`, and stream results into the live dashboard. Passwords are held in memory only.
- **Results Summary, AI Chat, Failures, Anomalies.** Optional AI analysis through a locally installed [Gemini CLI](https://github.com/google-gemini/gemini-cli). Everything else is computed from test data with no network calls.
- **Load JTL and Help.** Load a finished run from a `.jtl` (use XML output for the full transaction tree) and read the built-in Help tab on viewing results and distributed runs.

### Install
Download `jmeter-insightdashboard.jar`, copy it into `<JMETER_HOME>/lib/ext/`, restart JMeter. Requires JMeter 5.6.3 and Java 11+.
