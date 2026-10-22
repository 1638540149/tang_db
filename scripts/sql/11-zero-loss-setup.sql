CREATE TABLE crash_demo (id INT PRIMARY KEY, val VARCHAR(32));
INSERT INTO crash_demo (id, val) VALUES (1, 'v01'), (2, 'v02'), (3, 'v03'), (4, 'v04'), (5, 'v05'), (6, 'v06'), (7, 'v07'), (8, 'v08'), (9, 'v09'), (10, 'v10'), (11, 'v11'), (12, 'v12'), (13, 'v13'), (14, 'v14'), (15, 'v15'), (16, 'v16'), (17, 'v17'), (18, 'v18'), (19, 'v19'), (20, 'v20');
SELECT * FROM crash_demo;
