# tang_db JMeter JDBC load plan

This plan uses JMeter 5.6.3's built-in JDBC Request sampler with MySQL Connector/J. It connects to the FE MySQL protocol endpoint, so requests travel through FE to BE. It does not use HTTP samplers.

The JMX file was loaded and launched by the installed JMeter 5.6.3 CLI to validate the plan structure and JDBC driver classpath. No FE/BE service was running during that check, so it is not a database load-test result. Run the steps below with the FE/BE test services active to collect real measurements.

## Prepare the test database

Start FE and BE on the isolated test ports, with the FE MySQL listener on port 12346 by default. The plan's setUp Thread Group creates a unique database for each wrapper run, then creates two test tables and inserts 20 deterministic read rows. `prepare-db.sql` is provided for manual CLI setup if you open the plan directly in the JMeter GUI; in that case set the JMeter property `db=verify_jmeter` and do not run the setUp group against a database that already exists.

The wrapper never drops or overwrites an existing database. It saves the unique database name and a matching `cleanup-db.sql` in the result directory. The plan inserts rows into `jmeter_writes`; it does not delete test results.

## Driver setup

The MySQL Connector/J 8.0.33 JAR is in `lib/`. The command below adds it to JMeter's user classpath without modifying the JMeter installation.

## Run from PowerShell

Start the isolated FE/BE services first, then run from this directory (or use the absolute script path):

```powershell
powershell -ExecutionPolicy Bypass -File .\run-load.ps1 `
  -Threads 8 -RampSeconds 10 -DurationSeconds 60
```

The wrapper checks that the FE endpoint is reachable, creates a timestamped result directory, and invokes the installed JMeter. It defaults to `D:\dev\apache-jmeter-5.6.3`; override with `-JMeterHome` if needed. Open `results\<run>\html-report\index.html` after the run. The JTL contains per-sample response times and errors. The test plan performs a primary-key read, a filtered aggregate, and a unique-key insert per loop. Override `-Threads`, `-RampSeconds`, and `-DurationSeconds` to run a load ladder; begin with 1, 4, 8, then 16 threads and stop if errors or response times rise sharply.

After the run, check writes with `SELECT COUNT(*), COUNT(DISTINCT id) FROM verify_jmeter.jmeter_writes;`. Both values should match if every insert succeeded.

Each wrapper run passes a unique schema name to the setUp group and saves it in `database.txt`. It also writes a matching `cleanup-db.sql` in that run's result directory. When checking writes, use the schema name from `database.txt` in `SELECT COUNT(*), COUNT(DISTINCT id) FROM <database>.jmeter_writes;`.

## What the measurements mean

JMeter reports sampler throughput and response-time percentiles for JDBC requests. Each thread gets its own pooled connection (`poolMax=0`), so thread count also drives FE connection concurrency. Do not enable a large View Results Tree listener during load. Keep the FE/BE ports isolated from normal development services.

The existing project-authored JDBC suite remains the correctness and crash-recovery evidence. JMeter adds sustained concurrent load measurements; it does not replace exact data/checksum assertions, forced BE termination, or WAL replay verification.
