-- SPEC-P5 I 组:JSON 列 + json_extract(嵌套对象 / 数组下标 / 路径不存在)
CREATE TABLE p5_doc (id INT PRIMARY KEY, doc JSON);
INSERT INTO p5_doc VALUES (1, '{"user":{"name":"alice","tags":["vip","new"]},"score":9.5}');
INSERT INTO p5_doc VALUES (2, '{"user":{"name":"bob","tags":[]}}');
SELECT id, json_extract(doc,'$.user.name') AS name,
       json_extract(doc,'$.user.tags[0]') AS first_tag,
       json_extract(doc,'$.score') AS score,
       json_extract(doc,'$.missing.path') AS missing FROM p5_doc ORDER BY id;
