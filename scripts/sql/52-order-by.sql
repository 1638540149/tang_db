-- SPEC-P5 C 组:多列排序 / NULL 位置 / TopN / LIMIT offset
CREATE TABLE p5_sort (id INT PRIMARY KEY, grp VARCHAR(8), v INT);
INSERT INTO p5_sort VALUES (1,'a',10),(2,'a',NULL),(3,'b',30),(4,'b',20),(5,'c',20);
SELECT id, grp, v FROM p5_sort ORDER BY grp ASC, v ASC;
SELECT id, grp, v FROM p5_sort ORDER BY grp DESC, v DESC;
SELECT id FROM p5_sort ORDER BY v DESC LIMIT 2;
SELECT id FROM p5_sort ORDER BY v DESC LIMIT 2 OFFSET 1;
