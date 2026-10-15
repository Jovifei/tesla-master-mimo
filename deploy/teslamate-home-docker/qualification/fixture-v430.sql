-- Synthetic only. Apply exclusively to the disposable freshly migrated compat430 DB.
\set ON_ERROR_STOP on
BEGIN;
CREATE TABLE matelink_compat_fixture (marker text PRIMARY KEY);
INSERT INTO matelink_compat_fixture VALUES ('SYNTHETIC-COMPAT430-20261015');
INSERT INTO car_settings (id) VALUES (9000),(9001);
INSERT INTO cars (id,eid,vid,vin,name,settings_id,inserted_at,updated_at)
VALUES (7,700000,700001,'SYNTHETIC-NOT-A-VIN-7','Synthetic7',9000,'2026-10-01','2026-10-01'),
       (8,800000,800001,'SYNTHETIC-NOT-A-VIN-8','Synthetic8',9001,'2026-10-01','2026-10-01');
INSERT INTO drives (id,car_id,start_date,end_date) VALUES
 (100,7,'2026-10-01T00:00:00','2026-10-01T00:10:00'),
 (101,7,'2026-10-01T01:00:00','2026-10-01T01:10:00'),
 (102,7,'2026-10-01T02:00:00','2026-10-01T02:10:00'),
 (200,8,'2026-10-01T00:00:00','2026-10-01T00:10:00');
INSERT INTO positions (id,car_id,drive_id,date,latitude,longitude,speed,power,odometer,battery_level) VALUES
 (1000,7,100,'2026-10-01T00:01:00',0,0,0,-3,100,70),
 (1001,7,100,'2026-10-01T00:10:00',0,0,NULL,0,101,69),
 (1002,7,101,'2026-10-01T01:00:00',0,0,0,0,101,70),
 (1003,7,NULL,'2026-10-01T01:11:00',0,0,NULL,-1,101,NULL),
 (1004,7,NULL,'2026-10-01T01:12:00',0,0,NULL,0,101,NULL),
 (1005,7,102,'2026-10-01T02:00:00',0,0,0,-2,101,70),
 (2000,8,200,'2026-10-01T00:01:00',0,0,0,-99,900,50);
UPDATE drives SET start_position_id=1000,end_position_id=1001 WHERE id=100;
UPDATE drives SET start_position_id=1002,end_position_id=1002 WHERE id=101;
UPDATE drives SET start_position_id=1005,end_position_id=1005 WHERE id=102;
INSERT INTO charging_processes (id,car_id,position_id,start_date,end_date,charge_energy_added,cost)
VALUES (501,7,1001,'2026-10-01T00:20:00','2026-10-01T00:40:00',0,-1),
       (601,8,2000,'2026-10-01T00:20:00','2026-10-01T00:40:00',10,5);
INSERT INTO charges (id,date,charging_process_id,charge_energy_added,charger_power,ideal_battery_range_km,battery_level)
VALUES (5001,'2026-10-01T00:30:00',501,0,0,0,NULL),
       (6001,'2026-10-01T00:30:00',601,10,7,100,50);
COMMIT;
