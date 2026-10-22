CREATE TABLE tmp_demo (id INT PRIMARY KEY, note VARCHAR(16));
INSERT INTO tmp_demo (id, note) VALUES (1, 'hello');
SELECT * FROM tmp_demo;
TRUNCATE TABLE tmp_demo;
SELECT * FROM tmp_demo;
DROP TABLE tmp_demo;
SHOW TABLES;
