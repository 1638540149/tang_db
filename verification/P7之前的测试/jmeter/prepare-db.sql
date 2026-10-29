-- Run once through tang_db's MySQL CLI or an existing JDBC client.
-- This is an isolated test database. The CREATE DATABASE statement fails if it already exists.
CREATE DATABASE verify_jmeter;
CREATE TABLE verify_jmeter.jmeter_rows (
  id INT PRIMARY KEY,
  bucket INT NOT NULL,
  amount INT NOT NULL
);
CREATE TABLE verify_jmeter.jmeter_writes (
  id BIGINT PRIMARY KEY,
  worker_id INT NOT NULL,
  payload INT NOT NULL
);
INSERT INTO verify_jmeter.jmeter_rows (id, bucket, amount) VALUES
(1,1,101),(2,2,202),(3,3,303),(4,4,404),(5,5,505),
(6,6,606),(7,7,707),(8,8,808),(9,9,909),(10,0,110),
(11,1,211),(12,2,312),(13,3,413),(14,4,514),(15,5,615),
(16,6,716),(17,7,817),(18,8,918),(19,9,119),(20,0,220);
