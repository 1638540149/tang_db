INSERT INTO users (id, name, city, age) VALUES (2, 'lisi', '北京', 20), (3, 'zhangsan', '上海', 30), (4, 'wangwu', '北京', 28);
SELECT name, city FROM users WHERE city = '北京' AND age > 25;
