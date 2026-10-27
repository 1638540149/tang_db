-- SPEC-P5 E 组:IN (子查询) / 标量子查询 / 空集合
SELECT id, name FROM p5_user WHERE id IN (SELECT uid FROM p5_trade) ORDER BY id;
SELECT id FROM p5_user WHERE id IN (SELECT uid FROM p5_trade WHERE uid > 100);
