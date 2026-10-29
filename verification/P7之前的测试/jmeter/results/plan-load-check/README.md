# JMX structure check (not a load-test result)

JMeter 5.6.3 loaded the plan successfully (`Created the tree successfully`) and ran it for one second with the MySQL Connector/J JAR on its classpath. No FE listener was running at 127.0.0.1:12346, so the 168 samples in `samples.jtl` are connection failures. Do not use this JTL for performance conclusions. Run `run-load.ps1` with FE/BE active to create a real result directory.
